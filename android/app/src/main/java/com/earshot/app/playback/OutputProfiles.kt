package com.earshot.app.playback

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioDeviceInfo
import androidx.core.content.edit
import com.earshot.app.data.PlaybackDefaults
import com.earshot.app.data.Settings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import org.json.JSONObject

/** Where the sound is going (phase 20, C1). */
enum class Output(val label: String) {
    PHONE("Phone speaker"),
    HEADPHONES("Earbuds and headphones"),
    SPEAKER("Speaker"),
    CAR("Car"),
}

/** The audio settings an output remembers. Skip lengths stay the same everywhere. */
data class AudioProfile(val speed: Float, val smartSpeed: Boolean, val voiceBoost: Boolean)

/**
 * Phase 20 (C1): per-output profiles. When on, a speed / Smart Speed / Voice Boost change made in
 * the player is remembered for the output playing now (car, earbuds, speaker, phone), and comes
 * back when that output does. Outputs without a profile use the defaults; a podcast's own settings
 * still win. Profiles stay on this phone (the synced defaults are unchanged).
 */
object OutputProfiles {
    /** An output device as the phone reports it: an AudioDeviceInfo type and its product name. */
    data class Device(val type: Int, val name: String)

    private const val KEY_ON = "output_profiles"
    private const val KEY_PROFILES = "output_profile_map"
    private const val KEY_KINDS = "output_device_kinds"

    private val _current = MutableStateFlow(Output.PHONE)
    /** The output playing now, kept up to date by the playback service. */
    val current: StateFlow<Output> = _current.asStateFlow()

    private val _currentDevice = MutableStateFlow<String?>(null)
    /** The Bluetooth device playing now, by name (null when none), so it can be marked as a speaker or car. */
    val currentDevice: StateFlow<String?> = _currentDevice.asStateFlow()

    private var on: MutableStateFlow<Boolean>? = null
    private var profiles: MutableStateFlow<Map<Output, AudioProfile>>? = null
    private var kinds: MutableStateFlow<Map<String, Output>>? = null

    private fun prefs(context: Context) = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun enabled(context: Context): StateFlow<Boolean> = onFlow(context).asStateFlow()
    fun profiles(context: Context): StateFlow<Map<Output, AudioProfile>> = profileFlow(context).asStateFlow()
    fun deviceKinds(context: Context): StateFlow<Map<String, Output>> = kindFlow(context).asStateFlow()

    private fun onFlow(context: Context) = synchronized(this) {
        on ?: MutableStateFlow(prefs(context).getBoolean(KEY_ON, false)).also { on = it }
    }

    private fun profileFlow(context: Context) = synchronized(this) {
        profiles ?: MutableStateFlow(parseProfiles(prefs(context).getString(KEY_PROFILES, null))).also { profiles = it }
    }

    private fun kindFlow(context: Context) = synchronized(this) {
        kinds ?: MutableStateFlow(parseKinds(prefs(context).getString(KEY_KINDS, null))).also { kinds = it }
    }

    fun setEnabled(context: Context, value: Boolean) {
        onFlow(context).value = value
        prefs(context).edit { putBoolean(KEY_ON, value) }
    }

    fun forget(context: Context, output: Output) = saveProfiles(context, profileFlow(context).value - output)

    /** Marks a Bluetooth device (by name) as earbuds, a speaker or a car. */
    fun setDeviceKind(context: Context, name: String, kind: Output) {
        val map = kindFlow(context).value.toMutableMap().apply { if (kind == Output.HEADPHONES) remove(name) else put(name, kind) }
        kindFlow(context).value = map
        prefs(context).edit { putString(KEY_KINDS, kindsToJson(map)) }
        _current.value = classify(lastDevices, lastInCar, map)
    }

    private var lastDevices: List<Device> = emptyList()
    private var lastInCar = false

    /** The defaults that apply now: the synced defaults with the current output's profile on top. */
    fun effectiveDefaults(context: Context): Flow<PlaybackDefaults> =
        combine(Settings.playbackDefaults(context), onFlow(context), profileFlow(context), _current) { d, isOn, map, out ->
            apply(d, isOn, map, out)
        }

    fun effectiveNow(context: Context): PlaybackDefaults =
        apply(Settings.playbackDefaults(context).value, onFlow(context).value, profileFlow(context).value, _current.value)

    /**
     * Saves an audio change made in the player or the car: to the current output's profile when
     * profiles are on, otherwise to the defaults (as before).
     */
    fun update(context: Context, change: (PlaybackDefaults) -> PlaybackDefaults) {
        if (!onFlow(context).value) {
            Settings.updatePlaybackDefaults(context, change)
            return
        }
        val n = change(effectiveNow(context))
        saveProfiles(context, profileFlow(context).value + (_current.value to AudioProfile(n.speed, n.smartSpeed, n.voiceBoost)))
        // Skip lengths aren't per output: those still go to the defaults.
        val d = Settings.playbackDefaults(context).value
        if (n.skipBackMs != d.skipBackMs || n.skipForwardMs != d.skipForwardMs) {
            Settings.updatePlaybackDefaults(context) { it.copy(skipBackMs = n.skipBackMs, skipForwardMs = n.skipForwardMs) }
        }
    }

    /** Called by the playback service when devices come and go or the car connects. */
    fun setCurrent(context: Context, devices: List<Device>, inCar: Boolean) {
        lastDevices = devices
        lastInCar = inCar
        val out = classify(devices, inCar, kindFlow(context).value)
        _currentDevice.value = bluetoothName(devices)
        _current.value = out
    }

    private fun saveProfiles(context: Context, map: Map<Output, AudioProfile>) {
        profileFlow(context).value = map
        prefs(context).edit { putString(KEY_PROFILES, profilesToJson(map)) }
    }

    // ---- Pure parts (unit-tested) ----

    fun apply(d: PlaybackDefaults, on: Boolean, map: Map<Output, AudioProfile>, out: Output): PlaybackDefaults {
        if (!on) return d
        val p = map[out] ?: return d
        return d.copy(speed = p.speed, smartSpeed = p.smartSpeed, voiceBoost = p.voiceBoost)
    }

    // Newer device types are plain ints copied in at build time; older Android never reports them.
    @SuppressLint("InlinedApi")
    private val WIRED = setOf(
        AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
        AudioDeviceInfo.TYPE_USB_HEADSET,
    )
    @SuppressLint("InlinedApi")
    private val BLUETOOTH = setOf(
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET,
        AudioDeviceInfo.TYPE_BLE_SPEAKER, AudioDeviceInfo.TYPE_HEARING_AID,
    )

    /**
     * Android Auto is the car. Otherwise a Bluetooth device (a speaker if it says so or was marked
     * as one; a car if marked), then wired headphones, then the phone's own speaker.
     */
    fun classify(devices: List<Device>, inCar: Boolean, kinds: Map<String, Output>): Output {
        if (inCar) return Output.CAR
        devices.firstOrNull { it.type in BLUETOOTH }?.let { bt ->
            kinds[bt.name]?.let { return it }
            return if (bt.type == AudioDeviceInfo.TYPE_BLE_SPEAKER) Output.SPEAKER else Output.HEADPHONES
        }
        if (devices.any { it.type in WIRED }) return Output.HEADPHONES
        return Output.PHONE
    }

    fun bluetoothName(devices: List<Device>): String? =
        devices.firstOrNull { it.type in BLUETOOTH }?.name?.takeIf { it.isNotBlank() }

    fun profilesToJson(map: Map<Output, AudioProfile>): String = JSONObject().apply {
        map.forEach { (k, v) ->
            put(k.name, JSONObject().put("speed", v.speed.toDouble()).put("smartSpeed", v.smartSpeed).put("voiceBoost", v.voiceBoost))
        }
    }.toString()

    fun parseProfiles(json: String?): Map<Output, AudioProfile> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            Output.entries.mapNotNull { k ->
                o.optJSONObject(k.name)?.let { p ->
                    k to AudioProfile(p.optDouble("speed", 1.0).toFloat(), p.optBoolean("smartSpeed"), p.optBoolean("voiceBoost"))
                }
            }.toMap()
        }.getOrDefault(emptyMap())
    }

    fun kindsToJson(map: Map<String, Output>): String = JSONObject().apply { map.forEach { (k, v) -> put(k, v.name) } }.toString()

    fun parseKinds(json: String?): Map<String, Output> {
        if (json.isNullOrBlank()) return emptyMap()
        return runCatching {
            val o = JSONObject(json)
            o.keys().asSequence().mapNotNull { k -> runCatching { k to Output.valueOf(o.getString(k)) }.getOrNull() }.toMap()
        }.getOrDefault(emptyMap())
    }
}
