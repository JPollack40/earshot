package com.earshot.app.data.feed

import org.json.JSONObject

data class Chapter(val startMs: Long, val title: String, val imageUrl: String?)

/** Podcasting 2.0 JSON chapters (podcast:chapters). Embedded ID3 chapters come in a later phase. */
object ChaptersParser {
    fun parse(json: String): List<Chapter> {
        val array = JSONObject(json).optJSONArray("chapters") ?: return emptyList()
        val chapters = mutableListOf<Chapter>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            // toc=false marks silent markers that shouldn't appear in the chapter list
            if (o.has("toc") && !o.optBoolean("toc", true)) continue
            chapters += Chapter(
                startMs = (o.optDouble("startTime", 0.0) * 1000).toLong(),
                title = o.optString("title").ifBlank { "Chapter ${i + 1}" },
                imageUrl = o.optString("img").takeIf { it.isNotBlank() },
            )
        }
        return chapters.sortedBy { it.startMs }
    }
}
