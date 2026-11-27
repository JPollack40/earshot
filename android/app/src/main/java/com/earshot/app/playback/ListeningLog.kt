package com.earshot.app.playback

import android.util.Log
import com.earshot.app.EarshotApp
import com.earshot.app.data.db.Listen
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * Listening history (phase 7), kept for a future Stats page. The playback service calls [tick]
 * every second while playing and [finish] when playback pauses or stops. Each stretch of listening
 * (play → pause, a change of episode, or 30 minutes) becomes one row in `listens`, synced to
 * Supabase. Stretches under 5 seconds are dropped.
 *
 * A stretch in progress is saved every minute with changedAt = 0 (not uploaded yet); if the app is
 * killed before it finishes, [finishOrphans] at the next start uploads what was saved.
 */
class ListeningLog(private val app: EarshotApp) {
    private var current: Listen? = null
    private var lastSaveAt = 0L

    /** [elapsedMs] of real time just played of [episodeId], now at [positionMs]. */
    fun tick(episodeId: String, positionMs: Long, elapsedMs: Long, speed: Float, inCar: Boolean) {
        val now = System.currentTimeMillis()
        var c = current
        if (c != null && (c.episodeId != episodeId || now - c.startedAt > MAX_STRETCH_MS)) {
            finish()
            c = null
        }
        if (c == null) {
            c = Listen(
                id = UUID.randomUUID().toString(),
                episodeId = episodeId,
                startedAt = now - elapsedMs,
                endedAt = now,
                listenedMs = 0,
                fromMs = (positionMs - (elapsedMs * speed).toLong()).coerceAtLeast(0),
                toMs = positionMs,
                speed = speed,
                device = if (inCar) "car" else "phone",
                dirty = false,
                changedAt = 0,
            )
            lastSaveAt = now
        }
        current = c.copy(
            endedAt = now,
            listenedMs = c.listenedMs + elapsedMs,
            toMs = positionMs,
            speed = speed,
            device = if (inCar) "car" else c.device,
        )
        if (now - lastSaveAt >= 60_000) {
            lastSaveAt = now
            save(current!!, final = false)
        }
    }

    /** Playback paused, stopped or moved on: the current stretch is done. */
    fun finish() {
        val c = current ?: return
        current = null
        if (c.listenedMs < MIN_STRETCH_MS) return
        save(c, final = true)
    }

    private fun save(l: Listen, final: Boolean) {
        app.appScope.launch {
            try {
                val item = app.db.episodes().getWithPodcast(l.episodeId)
                val feedUrl = item?.let { app.db.podcasts().get(it.episode.podcastId)?.feedUrl }.orEmpty()
                app.db.listens().upsert(
                    l.copy(
                        feedUrl = feedUrl,
                        podcastTitle = item?.podcastTitle.orEmpty(),
                        episodeTitle = item?.episode?.title.orEmpty(),
                        dirty = final,
                        changedAt = if (final) System.currentTimeMillis() else 0,
                    ),
                )
            } catch (e: Exception) {
                Log.w("Earshot", "Couldn't save listening history", e)
            }
        }
    }

    /** At service start: stretches left unfinished by a killed process are finished and uploaded. */
    fun finishOrphans() {
        app.appScope.launch {
            runCatching { app.db.listens().finishOrphans(System.currentTimeMillis()) }
        }
    }

    private companion object {
        const val MAX_STRETCH_MS = 30L * 60 * 1000
        const val MIN_STRETCH_MS = 5_000L
    }
}
