package com.earshot.app.data.search

import com.earshot.app.BuildConfig
import com.earshot.app.data.net.Http
import org.json.JSONObject
import java.net.URLEncoder

/**
 * Podcast Index search through the Supabase function `podcast-search` (supabase/functions/podcast-search),
 * which holds the Podcast Index key and secret, so neither is in the app.
 * The project URL and publishable key come from android/local.properties (not committed):
 *   earshot.supabaseUrl=https://<project-ref>.supabase.co
 *   earshot.supabaseKey=sb_publishable_...
 */
object PodcastIndexSearch {
    val configured: Boolean get() = BuildConfig.SUPABASE_URL.isNotBlank() && BuildConfig.SUPABASE_KEY.isNotBlank()

    suspend fun search(term: String): List<SearchResult> {
        val q = URLEncoder.encode(term.trim(), "UTF-8")
        val json = Http.getText(
            "${BuildConfig.SUPABASE_URL.trimEnd('/')}/functions/v1/podcast-search?q=$q",
            headers = mapOf("apikey" to BuildConfig.SUPABASE_KEY),
        )
        val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
        val out = mutableListOf<SearchResult>()
        for (i in 0 until results.length()) {
            val o = results.optJSONObject(i) ?: continue
            val feed = o.optString("feedUrl")
            if (feed.isBlank()) continue
            out += SearchResult(
                title = o.optString("title"),
                author = o.optString("author"),
                feedUrl = feed,
                artworkUrl = o.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
                episodeCount = if (o.isNull("episodeCount")) null else o.optInt("episodeCount"),
            )
        }
        return out.distinctBy { it.feedUrl }
    }
}
