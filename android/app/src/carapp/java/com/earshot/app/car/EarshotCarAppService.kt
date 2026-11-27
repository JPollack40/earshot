package com.earshot.app.car

import android.content.Intent
import android.support.v4.media.session.MediaSessionCompat
import android.util.Log
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.annotations.ExperimentalCarApi
import androidx.car.app.media.MediaPlaybackManager
import androidx.car.app.media.model.MediaPlaybackTemplate
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.earshot.app.EarshotApp
import com.earshot.app.data.db.EpisodeWithPodcast
import com.earshot.app.playback.PlaybackService
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Phase 12 scaffold: Earshot's custom car screens, as a Car App Library "templated media app"
 * (Beta, Car API level 8). Only in the twoIcons and lab variants (-Pearshot.carApp=true).
 *
 * The normal media service (PlaybackService) stays: it plays, and the car host builds its playback
 * controls from its session. This app only draws the screens. First screen: the queue; tapping an
 * episode plays it and opens the playback screen.
 *
 * What to learn first (Desktop Head Unit, twoIcons variant): does Android Auto show two Earshot
 * icons (the media app and this one), one, or only this one?
 */
class EarshotCarAppService : CarAppService() {
    // Scaffold: any host may connect. Before friends get it, switch to an allowlist
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

    override fun onCreateScreen(intent: Intent): Screen = QueueScreen(carContext)

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

/** The queue, as a list. Tapping an episode plays it and opens the playback screen. */
class QueueScreen(carContext: CarContext) : Screen(carContext) {
    private val app = carContext.applicationContext as EarshotApp
    private var queue: List<EpisodeWithPodcast>? = null

    init {
        lifecycleScope.launch {
            app.repository.observeQueue().collect {
                queue = it
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val items = queue
        val header = Header.Builder()
            .setTitle("Queue")
            .setStartHeaderAction(Action.APP_ICON)
            // Action.MEDIA_PLAYBACK only exists from Car App Library 1.8; on 1.7 a plain action does the job.
            .addEndHeaderAction(
                Action.Builder()
                    .setTitle("Now playing")
                    .setOnClickListener { screenManager.push(PlaybackScreen(carContext)) }
                    .build(),
            )
            .build()
        val builder = ListTemplate.Builder().setHeader(header)
        if (items == null) return builder.setLoading(true).build()
        val list = ItemList.Builder().setNoItemsMessage("Your queue is empty")
        items.take(MAX_ROWS).forEach { item ->
            list.addItem(
                Row.Builder()
                    .setTitle(item.episode.title.ifBlank { "Untitled" })
                    .addText(item.podcastTitle)
                    .setOnClickListener {
                        app.player.play(item)
                        screenManager.push(PlaybackScreen(carContext))
                    }
                    .build(),
            )
        }
        return builder.setSingleList(list.build()).build()
    }

    private companion object {
        /** Hosts cap list length (often 6 to 100); a real version asks ConstraintManager. */
        const val MAX_ROWS = 20
    }
}

/** The playback screen: the host draws artwork, title, progress and controls from the media session. */
class PlaybackScreen(carContext: CarContext) : Screen(carContext) {
    @OptIn(ExperimentalCarApi::class)
    override fun onGetTemplate(): Template =
        MediaPlaybackTemplate.Builder()
            .setHeader(
                Header.Builder()
                    .setTitle("Now playing")
                    .setStartHeaderAction(Action.BACK)
                    .build(),
            )
            .build()
}
