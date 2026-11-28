package com.earshot.app.playback

import android.media.AudioDeviceInfo
import com.earshot.app.data.PlaybackDefaults
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OutputProfilesTest {
    private val speaker = OutputProfiles.Device(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER, "Galaxy Z Fold6")
    private val buds = OutputProfiles.Device(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, "Galaxy Buds3 Pro")
    private val wired = OutputProfiles.Device(AudioDeviceInfo.TYPE_WIRED_HEADPHONES, "Headphones")
    private val bleSpeaker = OutputProfiles.Device(AudioDeviceInfo.TYPE_BLE_SPEAKER, "Kitchen")

    @Test fun classifiesOutputs() {
        assertEquals(Output.PHONE, OutputProfiles.classify(listOf(speaker), false, emptyMap()))
        assertEquals(Output.HEADPHONES, OutputProfiles.classify(listOf(speaker, buds), false, emptyMap()))
        assertEquals(Output.HEADPHONES, OutputProfiles.classify(listOf(speaker, wired), false, emptyMap()))
        assertEquals(Output.SPEAKER, OutputProfiles.classify(listOf(speaker, bleSpeaker), false, emptyMap()))
        // Android Auto wins over everything.
        assertEquals(Output.CAR, OutputProfiles.classify(listOf(speaker, buds), true, emptyMap()))
        // A Bluetooth device marked as a speaker or a car.
        assertEquals(Output.SPEAKER, OutputProfiles.classify(listOf(speaker, buds), false, mapOf("Galaxy Buds3 Pro" to Output.SPEAKER)))
        assertEquals(Output.CAR, OutputProfiles.classify(listOf(buds), false, mapOf("Galaxy Buds3 Pro" to Output.CAR)))
    }

    @Test fun bluetoothNameOnlyForBluetooth() {
        assertEquals("Galaxy Buds3 Pro", OutputProfiles.bluetoothName(listOf(speaker, buds)))
        assertNull(OutputProfiles.bluetoothName(listOf(speaker, wired)))
    }

    @Test fun profileSitsOnTopOfDefaultsOnlyWhenOn() {
        val d = PlaybackDefaults(speed = 1.2f, smartSpeed = false, voiceBoost = false, skipBackMs = 10_000, skipForwardMs = 30_000)
        val map = mapOf(Output.CAR to AudioProfile(1.0f, smartSpeed = false, voiceBoost = true))
        assertEquals(d, OutputProfiles.apply(d, false, map, Output.CAR))
        assertEquals(d, OutputProfiles.apply(d, true, map, Output.HEADPHONES))
        val car = OutputProfiles.apply(d, true, map, Output.CAR)
        assertEquals(1.0f, car.speed, 1e-4f)
        assertEquals(true, car.voiceBoost)
        assertEquals(10_000L, car.skipBackMs)
    }

    @Test fun jsonRoundTrips() {
        val map = mapOf(Output.CAR to AudioProfile(1.1f, true, false), Output.HEADPHONES to AudioProfile(1.5f, false, true))
        assertEquals(map, OutputProfiles.parseProfiles(OutputProfiles.profilesToJson(map)))
        val kinds = mapOf("Kenwood" to Output.CAR)
        assertEquals(kinds, OutputProfiles.parseKinds(OutputProfiles.kindsToJson(kinds)))
        assertEquals(emptyMap<Output, AudioProfile>(), OutputProfiles.parseProfiles("not json"))
    }
}
