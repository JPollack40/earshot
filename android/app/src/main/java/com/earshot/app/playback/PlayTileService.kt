package com.earshot.app.playback

import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.earshot.app.EarshotApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * Phase 20 (C6): an Earshot tile in Quick Settings. Tap to play or pause; it shows what's playing. With
 * nothing loaded, play starts the top of the queue (as the app's mini player does).
 */
class PlayTileService : TileService() {
    private var scope: CoroutineScope? = null
    private var watch: Job? = null

    private val app get() = application as EarshotApp

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        scope = s
        app.player.connect()
        watch = s.launch {
            app.player.state.map { it.isPlaying to it.title }.distinctUntilChanged().collect { (playing, title) -> show(playing, title) }
        }
    }

    override fun onStopListening() {
        watch?.cancel()
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        app.player.togglePlay()
    }

    private fun show(playing: Boolean, title: String) {
        val t = qsTile ?: return
        t.state = if (playing) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        t.label = "Earshot"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) t.subtitle = if (playing) title.take(40) else if (title.isNotBlank()) "Paused" else "Play queue"
        t.contentDescription = if (playing) "Pause $title" else "Play"
        t.updateTile()
    }
}
