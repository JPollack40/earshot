package com.earshot.app.data.search

import android.util.Log
import kotlinx.coroutines.CancellationException

/** Which directory answered a search. */
enum class SearchSource { PODCAST_INDEX, APPLE }

data class SearchOutcome(val results: List<SearchResult>, val source: SearchSource, val fellBack: Boolean)

/**
 * Podcast directory search: Podcast Index (through Supabase) when it's set up, Apple's search otherwise,
 * or if Podcast Index fails, so search keeps working either way.
 */
object Directory {
    suspend fun search(term: String): SearchOutcome {
        if (PodcastIndexSearch.configured) {
            try {
                return SearchOutcome(PodcastIndexSearch.search(term), SearchSource.PODCAST_INDEX, fellBack = false)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("Earshot", "Podcast Index search failed, using Apple's search", e)
            }
        }
        return SearchOutcome(ItunesSearch.search(term), SearchSource.APPLE, fellBack = PodcastIndexSearch.configured)
    }
}
