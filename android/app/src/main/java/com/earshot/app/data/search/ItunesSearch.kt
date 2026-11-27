package com.earshot.app.data.search

import com.earshot.app.data.net.Http
import org.json.JSONObject
import java.net.URLEncoder

data class SearchResult(
    val title: String,
    val author: String,
    val feedUrl: String,
    val artworkUrl: String?,
    /** Known for Podcast Index results only. */
    val episodeCount: Int? = null,
)

/**
 * Directory search through Apple's public, keyless search API. Used when Podcast Index isn't set up
 * or fails (see [Directory]).
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
        // Apple sometimes lists the same feed twice (e.g. "radiolab"); the results list needs unique feeds.
        return out.distinctBy { it.feedUrl }
    }
}
