package com.earshot.app.car

import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.earshot.app.EarshotApp
import com.earshot.app.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 12: Earshot's custom car screens, as a Car App Library "templated media app" (Beta, Car
 * API level 8). Only in the twoIcons and lab variants (-Pearshot.carApp=true).
 *
 * Learned from the two-icon test (2026-11-27): an app that declares this service gets ONE icon in
 * Android Auto, and it opens these screens instead of the media browser. So the custom screens
 * live in "Earshot Lab" (lab variant) for now; later they move into Earshot behind a Settings switch.
 *
 * The normal media service (PlaybackService) stays: it plays, and the car host draws the playback
 * screen's controls from its session. These classes only draw the screens (see CarScreens.kt).
 */
class EarshotCarAppService : CarAppService() {
    // Testing: any host may connect. Before friends get it, switch to an allowlist
    // (HostValidator.Builder(...).addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)).
    override fun createHostValidator(): HostValidator = HostValidator.ALLOW_ALL_HOSTS_VALIDATOR

    override fun onCreateSession(sessionInfo: SessionInfo): Session = EarshotCarSession()
}

class EarshotCarSession : Session() {
    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                registerPlaybackToken()
            }
        })
    }

    override fun onCreateScreen(intent: Intent): Screen = HomeScreen(carContext)

    /**
     * The host needs the media session's token before it can show MediaPlaybackTemplate. The
     * playback service runs in this process; connecting the app's player starts it.
     */
    private fun registerPlaybackToken() {
        val app = carContext.applicationContext as EarshotApp
        app.player.connect()
        lifecycleScope.launch {
            repeat(50) {
                val token = PlaybackService.platformToken
                if (token != null) {
                    val manager = carContext.getCarService(CarContext.MEDIA_PLAYBACK_SERVICE) as MediaPlaybackManager
                    manager.registerMediaPlaybackToken(MediaSessionCompat.Token.fromToken(token))
                    Log.i(TAG, "Media session token registered with the car host")
                    return@launch
                }
                delay(100)
            }
            Log.w(TAG, "Playback service didn't start within 5 s; the playback screen won't work")
        }
    }

    private companion object {
        const val TAG = "EarshotCar"
    }
}
