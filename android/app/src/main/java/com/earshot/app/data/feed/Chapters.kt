package com.earshot.app.data.feed

import org.json.JSONArray
import org.json.JSONObject

/**
 * A chapter. [url] is the chapter's link (Podcasting 2.0 "url", ID3 WXXX); [endMs] is known for
 * some embedded chapters, otherwise a chapter runs until the next one starts.
 * [generated] marks chapters made from the transcript (phase 11).
 */
data class Chapter(
    val startMs: Long,
    val title: String,
    val imageUrl: String?,
    val url: String? = null,
    val endMs: Long? = null,
    val generated: Boolean = false,
)

/** Podcasting 2.0 JSON chapters (podcast:chapters). Embedded chapters: see EmbeddedChapters. */
object ChaptersParser {
    fun parse(json: String): List<Chapter> {
        val array = JSONObject(json).optJSONArray("chapters") ?: return emptyList()
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // toc=false marks silent markers that shouldn't appear in the chapter list
            if (o.has("toc") && !o.optBoolean("toc", true)) continue
            val start = (o.optDouble("startTime", 0.0) * 1000).toLong()
            chapters += Chapter(
                startMs = start,
                title = o.optString("title").ifBlank { "Chapter ${i + 1}" },
                imageUrl = o.optString("img").takeIf { it.startsWith("http") },
                url = o.optString("url").takeIf { it.startsWith("http") },
                endMs = o.optDouble("endTime", Double.NaN).takeIf { !it.isNaN() }?.let { (it * 1000).toLong() }?.takeIf { it > start },
            )
        }
        return chapters.sortedBy { it.startMs }
    }

    /** The app's own cache format (also used for embedded and generated chapters). */
    fun toJson(list: List<Chapter>): String {
        val a = JSONArray()
        for (c in list) {
            a.put(
                JSONObject().put("startTime", c.startMs / 1000.0).put("title", c.title)
                    .apply {
                        c.imageUrl?.let { put("img", it) }
                        c.url?.let { put("url", it) }
                        c.endMs?.let { put("endTime", it / 1000.0) }
                    },
            )
        }
        return JSONObject().put("version", "1.2.0").put("chapters", a).toString()
    }
}

/** Where each chapter ends: its own end, else the next chapter's start, else the episode's end. */
fun Chapter.end(next: Chapter?, durationMs: Long): Long =
    endMs ?: next?.startMs ?: durationMs.takeIf { it > 0 } ?: Long.MAX_VALUE

/** Skipped chapters are stored as a JSON array of their start times (ms). */
object SkippedChapters {
    fun parse(json: String?): Set<Long> {
        if (json.isNullOrBlank()) return emptySet()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).map { a.getLong(it) }.toSet()
        }.getOrDefault(emptySet())
    }

    fun toJson(set: Set<Long>): String? = if (set.isEmpty()) null else JSONArray(set.sorted()).toString()

    /**
     * If [positionMs] is inside a skipped chapter, where playback should jump to: the end of that
     * chapter, carrying on past any skipped chapters that follow. Null when not in one.
     * A chapter matches a skipped start time within 1 s (generated or re-encoded chapters can drift).
     */
    fun skipTarget(chapters: List<Chapter>, skipped: Set<Long>, positionMs: Long, durationMs: Long): Long? {
        if (skipped.isEmpty() || chapters.isEmpty()) return null
        fun isSkipped(c: Chapter) = skipped.any { kotlin.math.abs(it - c.startMs) <= 1_000 }
        val i = chapters.indexOfLast { it.startMs <= positionMs }
        if (i < 0 || !isSkipped(chapters[i])) return null
        var j = i
        var target = chapters[j].end(chapters.getOrNull(j + 1), durationMs)
        if (positionMs >= target) return null
        while (j + 1 < chapters.size && isSkipped(chapters[j + 1]) && chapters[j + 1].startMs <= target + 1_000) {
            j++
            target = chapters[j].end(chapters.getOrNull(j + 1), durationMs)
        }
        return target
    }
}
