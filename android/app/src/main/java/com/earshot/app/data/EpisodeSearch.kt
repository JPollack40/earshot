package com.earshot.app.data

/**
 * Keyword search within one podcast's episodes, run on the phone against titles and show notes.
 * Every word must appear (in any order, in the title or the notes); "quoted phrases" must appear as written.
 * Case-insensitive; HTML in show notes is ignored.
 */
object EpisodeSearch {
    /** Lower-cased words and "quoted phrases" from what was typed. */
    fun terms(query: String): List<String> {
        val out = mutableListOf<String>()
        Regex(""""([^"]+)"|(\S+)""").findAll(query.lowercase()).forEach { m ->
            val t = (m.groups[1]?.value ?: m.groups[2]?.value).orEmpty().replace("\"", "").trim()
            if (t.isNotEmpty()) out += t.replace(space, " ")
        }
        return out
    }

    /** The searchable text for an episode: title plus plain-text show notes, lower-cased. */
    fun index(title: String, description: String): String = (title + "\n" + plainText(description)).lowercase()

    fun matches(index: String, terms: List<String>): Boolean = terms.all { index.contains(it) }

    /**
     * A short piece of the show notes around the first term found there, or null if the terms are only
     * in the title. Shown under the episode so it's clear why it matched.
     */
    fun snippet(description: String, terms: List<String>, radius: Int = 60): String? {
        val text = plainText(description)
        val lower = text.lowercase()
        val hit = terms.mapNotNull { t -> lower.indexOf(t).takeIf { it >= 0 }?.let { it to t.length } }.minByOrNull { it.first }
            ?: return null
        val start = (hit.first - radius).coerceAtLeast(0).let { s -> if (s == 0) 0 else text.indexOf(' ', s).let { if (it in s until hit.first) it + 1 else s } }
        val endRaw = (hit.first + hit.second + radius).coerceAtMost(text.length)
        val end = if (endRaw == text.length) endRaw else text.lastIndexOf(' ', endRaw).let { if (it > hit.first + hit.second) it else endRaw }
        return (if (start > 0) "…" else "") + text.substring(start, end).trim() + (if (end < text.length) "…" else "")
    }

    private val tag = Regex("<[^>]*>")
    private val numericEntity = Regex("&#(x?)([0-9a-fA-F]+);")
    private val space = Regex("""\s+""")
    private val named = mapOf(
        "&amp;" to "&", "&lt;" to "<", "&gt;" to ">", "&quot;" to "\"", "&apos;" to "'", "&#39;" to "'",
        "&nbsp;" to " ", "&rsquo;" to "’", "&lsquo;" to "‘", "&rdquo;" to "”", "&ldquo;" to "“",
        "&mdash;" to "—", "&ndash;" to "–", "&hellip;" to "…",
    )

    /** Show notes without HTML tags, entities decoded, whitespace collapsed. */
    fun plainText(html: String): String {
        var s = tag.replace(html, " ")
        for ((k, v) in named) s = s.replace(k, v, ignoreCase = true)
        s = numericEntity.replace(s) { m ->
            val n = m.groupValues[2].toIntOrNull(if (m.groupValues[1].isEmpty()) 10 else 16)
            if (n != null && Character.isValidCodePoint(n)) String(Character.toChars(n)) else m.value
        }
        // Curly apostrophes and quotes match their straight versions as typed on a keyboard.
        s = s.replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')
        return space.replace(s, " ").trim()
    }
}
