package com.earshot.app.work

import android.content.Context
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.earshot.app.EarshotApp
import com.earshot.app.data.articles.Article
import com.earshot.app.data.articles.ArticleText
import com.earshot.app.data.articles.Articles
import com.earshot.app.data.articles.Wav
import com.earshot.app.data.db.Episode
import com.earshot.app.data.db.Podcast
import com.earshot.app.data.net.Http
import com.earshot.app.data.transcript.Segment
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.resume

/**
 * Phase 23: turns a shared web article into an episode of "Articles", read aloud by the phone's own
 * text-to-speech voice, and adds it to the end of the queue. The page is fetched once; reading it aloud
 * works offline. The audio is a WAV file in the app's own storage (filesDir/articles), the transcript is
 * the article's paragraphs, each timed to the audio.
 */
class ArticleWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val url = inputData.getString(KEY_URL) ?: return Result.failure()
        val app = applicationContext as EarshotApp
        val id = Articles.episodeId(url)
        runCatching { setForeground(foregroundInfo("Getting the article", url, 0, 0)) }
        return try {
            if (app.db.episodes().get(id) != null) {
                app.repository.queueLast(id) // already made: just queue it again
                notifyDone("Already in Articles", "Added to the end of your queue.")
                return Result.success()
            }
            val html = Http.getText(url)
            val article = ArticleText.extract(html, url)
            if (article.words < MIN_WORDS) {
                notifyDone("Couldn't read that page", "It has too little text (it may need a sign-in or an app).")
                return Result.failure()
            }
            make(app, id, url, article)
            notifyDone("Article ready", "\"${article.title}\" is at the end of your queue.")
            prune(app)
            Result.success()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Article failed for $url", e)
            if (e is java.io.IOException && runAttemptCount < 2) {
                Result.retry()
            } else {
                notifyDone("Couldn't make the article", e.message ?: e.toString())
                Result.failure()
            }
        }
    }

    private suspend fun make(app: EarshotApp, id: String, url: String, article: Article) {
        val dir = File(app.filesDir, DIR).apply { mkdirs() }
        val parts = File(app.cacheDir, "article-parts").apply { deleteRecursively(); mkdirs() }
        val out = File(dir, "$id.wav")
        val tts = openVoice(app)
        try {
            val pieces = ArticleText.pieces(article.paragraphs, maxOf(500, minOf(TextToSpeech.getMaxSpeechInputLength() - 100, 3500)))
            val segments = mutableListOf<Segment>()
            var at = 0L
            Wav.Writer(out).use { w ->
                pieces.forEachIndexed { i, text ->
                    runCatching { setForeground(foregroundInfo("Reading the article aloud", article.title, i, pieces.size)) }
                    val part = File(parts, "$i.wav")
                    speakToFile(tts, text, part)
                    val ms = w.add(Wav.read(part.readBytes()))
                    part.delete()
                    segments += Segment(at, at + ms, text)
                    at += ms
                }
                w.finish()
            }
            val db = app.db
            val podcastId = articlesShow(app)
            db.episodes().insertAll(
                listOf(
                    Episode(
                        id = id,
                        podcastId = podcastId,
                        guid = url,
                        title = article.title,
                        description = "Read aloud from ${article.site}, ${article.words} words.\n$url",
                        pubDate = System.currentTimeMillis(),
                        audioUrl = Uri.fromFile(out).toString(),
                        mimeType = "audio/wav",
                        durationMs = at,
                        imageUrl = article.imageUrl,
                    ),
                ),
            )
            app.transcripts.save(id, SOURCE, segments, timed = true)
            app.repository.queueLast(id)
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            tts.shutdown()
            parts.deleteRecursively()
        }
    }

    /** The Articles show (made the first time; this phone only, never refreshed). */
    private suspend fun articlesShow(app: EarshotApp): Long {
        val dao = app.db.podcasts()
        dao.findByFeedUrl(Articles.FEED_URL)?.let { return it.id }
        return dao.insert(
            Podcast(
                feedUrl = Articles.FEED_URL,
                title = Articles.TITLE,
                author = "Read aloud on this phone",
                description = "Web articles you shared to Earshot, read aloud by the phone's voice. They stay on this phone.",
                lastRefreshedAt = System.currentTimeMillis(),
                complete = true, // no automatic refreshes
            ),
        )
    }

    /** Deletes article audio whose episode has gone (unfollowed, or removed from the library). */
    private suspend fun prune(app: EarshotApp) {
        val dir = File(app.filesDir, DIR)
        for (f in dir.listFiles().orEmpty()) {
            if (app.db.episodes().get(f.nameWithoutExtension) == null) f.delete()
        }
    }

    // ---- the phone's voice ----

    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private suspend fun openVoice(context: Context): TextToSpeech = withContext(Dispatchers.Main) {
        var engine: TextToSpeech? = null
        val status = withTimeout(20_000) {
            suspendCancellableCoroutine { cont ->
                engine = TextToSpeech(context) { s -> if (cont.isActive) cont.resume(s) }
            }
        }
        val tts = engine!!
        if (status != TextToSpeech.SUCCESS) {
            tts.shutdown()
            throw IllegalStateException("The phone's text-to-speech voice isn't available (Settings > Text-to-speech output)")
        }
        val lang = tts.setLanguage(Locale.getDefault())
        if (lang == TextToSpeech.LANG_MISSING_DATA || lang == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.US)
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) {
                pending.remove(utteranceId)?.complete(Unit)
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) {
                pending.remove(utteranceId)?.completeExceptionally(IllegalStateException("The voice couldn't read a part"))
            }
            override fun onError(utteranceId: String, errorCode: Int) {
                pending.remove(utteranceId)?.completeExceptionally(IllegalStateException("The voice couldn't read a part (error $errorCode)"))
            }
        })
        tts
    }

    private suspend fun speakToFile(tts: TextToSpeech, text: String, file: File) {
        val utterance = file.name + System.nanoTime()
        val done = CompletableDeferred<Unit>()
        pending[utterance] = done
        val r = tts.synthesizeToFile(text, Bundle(), file, utterance)
        if (r != TextToSpeech.SUCCESS) {
            pending.remove(utterance)
            throw IllegalStateException("The voice refused a part")
        }
        withTimeout(120_000) { done.await() }
    }

    // ---- notifications ----

    private fun foregroundInfo(title: String, text: String, done: Int, total: Int): ForegroundInfo {
        val n = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_TRANSCRIPTS)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(if (total > 0) "$title (${done + 1} of $total)" else title)
            .setContentText(text)
            .setProgress(total.coerceAtLeast(1), done, total == 0)
            .setOngoing(true)
            .setSilent(true)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, n)
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked just below
    private fun notifyDone(title: String, text: String) {
        val nm = NotificationManagerCompat.from(applicationContext)
        if (!nm.areNotificationsEnabled()) return
        val n = NotificationCompat.Builder(applicationContext, EarshotApp.CHANNEL_TRANSCRIPTS)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build()
        runCatching { nm.notify(NOTIFICATION_ID + 1, n) }
    }

    companion object {
        private const val TAG = "Earshot"
        private const val KEY_URL = "url"
        private const val DIR = "articles"
        private const val SOURCE = "article"
        private const val MIN_WORDS = 80
        private const val NOTIFICATION_ID = 41_000

        /** Makes (or re-queues) the article at [url]. */
        fun enqueue(context: Context, url: String) {
            val request = OneTimeWorkRequestBuilder<ArticleWorker>()
                .setInputData(workDataOf(KEY_URL to url))
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .addTag("article")
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork("article-" + Articles.episodeId(url), ExistingWorkPolicy.KEEP, request)
        }
    }
}
