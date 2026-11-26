package com.earshot.app.data.search

import com.earshot.app.data.net.Http
import org.json.JSONObject
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
)

/**
 * Directory search through Apple's public, keyless search API.
 * Phase 5 adds Podcast Index search through a Supabase function, so its API secret never sits on the phone.
 */
object ItunesSearch {
    suspend fun search(term: String): List<SearchResult> {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val json = Http.getText("https://itunes.apple.com/search?media=podcast&entity=podcast&limit=30&term=$q")
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val feed = o.optString("feedUrl")
            if (feed.isBlank()) continue
            out += SearchResult(
                title = o.optString("collectionName"),
                author = o.optString("artistName"),
                feedUrl = feed,
                artworkUrl = o.optString("artworkUrl600").ifBlank { o.optString("artworkUrl100") }.takeIf { it.isNotBlank() },
            )
        }
        return out
    }
}
