package com.earshot.app.data

import android.content.Context
import androidx.core.content.edit
import com.earshot.app.data.feed.Chapter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Phase 19 (B4): per-show chapter rules. "Always skip chapters whose title contains ..." for a podcast,
 * as a few words or phrases (for example "sponsor, housekeeping, patreon"). The player skips those
 * chapters as if you'd skipped them by hand, in every episode of the show. On this phone only.
 */
object ChapterRules {
    private const val PREFS = "chapter_rules"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version.asStateFlow()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The words for [podcastId], as typed (comma-separated). */
    fun text(context: Context, podcastId: Long): String = prefs(context).getString("p:$podcastId", null).orEmpty()

    fun words(context: Context, podcastId: Long): List<String> = parse(text(context, podcastId))

    fun set(context: Context, podcastId: Long, text: String) {
        val clean = parse(text).joinToString(", ")
        prefs(context).edit { if (clean.isEmpty()) remove("p:$podcastId") else putString("p:$podcastId", clean) }
        _version.value++
    }

    /** Words and phrases from "a, b; c" (lower case, blanks dropped, at most 20). */
    fun parse(text: String): List<String> =
        text.split(',', ';', '\n').map { it.trim().lowercase() }.filter { it.length >= 2 }.distinct().take(20)

    /** Start times of the chapters whose title contains any of [words]. */
    fun matching(chapters: List<Chapter>, words: List<String>): Set<Long> {
        if (words.isEmpty()) return emptySet()
        return chapters.filter { c -> val t = c.title.lowercase(); words.any { t.contains(it) } }.map { it.startMs }.toSet()
    }
}
