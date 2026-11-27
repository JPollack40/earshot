package com.earshot.app.data.transcript

import com.earshot.app.data.db.Bookmark
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Markdown exports (same layout as the web player's, so notes look alike wherever they came from):
 * an episode's bookmarks, all bookmarks grouped by episode, or an episode's full transcript with the
 * bookmarks marked where they fall.
 */
object MarkdownExport {
    /** What an export needs to know about an episode. */
    data class EpisodeInfo(
        val episodeId: String,
        val title: String,
        val podcastTitle: String,
        val audioUrl: String?,
        val pubDate: Long = 0,
    )

    /** A transcript, if there is one, for quoting. */
    data class TranscriptInfo(val segments: List<Segment>, val timed: Boolean, val source: String)

    /** "1:02:03" or "2:03" */
    fun clock(ms: Long): String {
        val t = ms.coerceAtLeast(0) / 1000
        val h = t / 3600
        val m = (t % 3600) / 60
        val s = t % 60
        return if (h > 0) "%d:%02d:%02d".format(Locale.US, h, m, s) else "%d:%02d".format(Locale.US, m, s)
    }

    private fun date(ms: Long): String = if (ms <= 0) "" else SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(ms))

    /** A file name for an export: letters, digits, spaces and dashes only. */
    fun fileName(base: String): String =
        base.replace(Regex("[^A-Za-z0-9 \\-]"), "").replace(Regex("\\s+"), " ").trim().take(80).ifEmpty { "Earshot" } + ".md"

    private fun header(e: EpisodeInfo): String = buildString {
        append("# ").append(e.title.ifBlank { "Episode" }).append("\n\n")
        append("**Podcast:** ").append(e.podcastTitle).append("  \n")
        if (e.pubDate > 0) append("**Published:** ").append(date(e.pubDate)).append("  \n")
        if (!e.audioUrl.isNullOrBlank()) append("**Audio:** <").append(e.audioUrl).append(">  \n")
        append('\n')
    }

    private fun quoteBlock(text: String): String = text.lines().joinToString("\n") { "> $it" }

    private fun bookmarkBlock(b: Bookmark, t: TranscriptInfo?): String = buildString {
        append("### ").append(clock(b.positionMs)).append("\n\n")
        val q = t?.let { TranscriptFormat.quote(it.segments, it.timed, b.startMs, b.endMs) }
        if (q != null) append(quoteBlock(q)).append("\n\n")
        if (b.note.isNotBlank()) append(b.note.trim()).append("\n\n")
        if (q == null && b.note.isBlank()) append("_No transcript for this moment yet._\n\n")
    }

    /** One episode's bookmarks, in order through the episode. */
    fun episodeBookmarks(e: EpisodeInfo, bookmarks: List<Bookmark>, t: TranscriptInfo?): String = buildString {
        append(header(e))
        append("## Bookmarks\n\n")
        if (bookmarks.isEmpty()) append("_No bookmarks._\n")
        for (b in bookmarks.sortedBy { it.positionMs }) append(bookmarkBlock(b, t))
    }.trimEnd() + "\n"

    /** Every bookmark, grouped by episode (most recently bookmarked episode first). */
    fun allBookmarks(groups: List<Triple<EpisodeInfo, List<Bookmark>, TranscriptInfo?>>, exportedAt: Long): String = buildString {
        append("# Earshot bookmarks\n\n")
        append("_Exported ").append(date(exportedAt)).append("_\n\n")
        for ((e, bookmarks, t) in groups) {
            append("## ").append(e.title.ifBlank { "Episode" }).append("\n\n")
            append("*").append(e.podcastTitle).append("*")
            if (e.pubDate > 0) append(" · ").append(date(e.pubDate))
            append("\n\n")
            for (b in bookmarks.sortedBy { it.positionMs }) {
                append(bookmarkBlock(b, t).replaceFirst("### ", "#### "))
            }
        }
    }.trimEnd() + "\n"

    /**
     * The whole transcript, one paragraph per segment with its time (and speaker when it changes),
     * with each bookmark marked just before the segment it falls in.
     */
    fun fullTranscript(e: EpisodeInfo, t: TranscriptInfo, bookmarks: List<Bookmark>): String = buildString {
        append(header(e))
        append("## Transcript\n\n")
        append(if (t.source == "deepgram") "_Transcribed by Deepgram._\n\n" else if (t.source == "gemini") "_Transcribed by Gemini._\n\n" else "_Transcript from the podcast's feed._\n\n")
        val pending = bookmarks.sortedBy { it.positionMs }.toMutableList()
        var lastSpeaker: String? = null
        for ((i, s) in t.segments.withIndex()) {
            if (t.timed) {
                val nextStart = t.segments.getOrNull(i + 1)?.startMs ?: Long.MAX_VALUE
                while (pending.isNotEmpty() && pending.first().positionMs < nextStart) {
                    val b = pending.removeAt(0)
                    append("> **🔖 Bookmark ").append(clock(b.positionMs)).append("**")
                    if (b.note.isNotBlank()) append(": ").append(b.note.trim().replace("\n", " "))
                    append("\n\n")
                }
            }
            if (s.speaker != null && s.speaker != lastSpeaker) {
                append("**").append(TranscriptFormat.speakerName(s.speaker)).append(":** ")
                lastSpeaker = s.speaker
            }
            if (t.timed) append("[").append(clock(s.startMs)).append("] ")
            append(s.text).append("\n\n")
        }
        if (!t.timed && pending.isNotEmpty()) {
            append("## Bookmarks\n\n")
            for (b in pending) append(bookmarkBlock(b, null))
        }
    }.trimEnd() + "\n"
}
