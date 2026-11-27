package com.earshot.app.data

import java.text.Normalizer

/**
 * Understanding "Hey Google, play … on Earshot" (phase 10). Android Auto and Assistant pass the
 * words after "play" as a search query, already without "on Earshot" most of the time, but not
 * always, and speech recognition spells show names loosely.
 */
object VoiceMatch {
    sealed interface Intent {
        /** "play podcasts", "play Earshot", nothing at all: carry on with what was playing, or the queue. */
        data object Resume : Intent

        /** "play the latest Morbid", "play the newest episode of Radiolab": that show's newest unplayed. */
        data class Latest(val show: String) : Intent

        /** Anything else: a show (its newest unplayed) or an episode title. */
        data class Any(val text: String) : Intent
    }

    private val RESUME_WORDS = setOf(
        "", "podcast", "podcasts", "my podcast", "my podcasts", "earshot", "my queue", "queue", "the queue",
        "something", "anything", "audio", "next", "next episode", "up next", "where i left off", "resume", "continue",
    )

    private val LATEST = Regex("^(?:the )?(?:latest|newest|new|most recent|last)(?: episode| one)?(?: (?:of|from))? (.+)$")
    private val LATEST_SUFFIX = Regex("^(?:the )?(.+?)(?:'s)? (?:latest|newest|new) episode$")

    fun normalize(s: String): String {
        val noAccents = Normalizer.normalize(s, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return noAccents.lowercase()
            .replace('’', '\'')
            .replace("&", " and ")
            .replace(Regex("[^a-z0-9' ]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    fun parse(query: String): Intent {
        var q = normalize(query)
        q = q.removePrefix("play ").removePrefix("listen to ").removePrefix("resume ").trim()
        q = q.replace(Regex(" (?:on|in|from|with|using) earshot$"), "").removeSuffix(" app").trim()
        q = q.replace(Regex("^(?:the )?(?:podcast|show) "), "").replace(Regex(" (?:podcast|show)$"), "").trim()
        if (q in RESUME_WORDS) return Intent.Resume
        LATEST.find(q)?.let { return Intent.Latest(it.groupValues[1].replace(Regex(" (?:podcast|show)$"), "").trim()) }
        LATEST_SUFFIX.find(q)?.let { return Intent.Latest(it.groupValues[1].trim()) }
        return Intent.Any(q)
    }

    private fun words(s: String) = normalize(s).split(' ').filter { it.isNotEmpty() && it !in STOP }
    private val STOP = setOf("the", "a", "an", "and", "of", "with", "podcast", "show")

    /**
     * How well [query] names [title], 0 to 1. Exact or contained matches score highest; otherwise
     * the share of query words found (allowing small spelling differences) in the title.
     */
    fun score(query: String, title: String): Double {
        val q = normalize(query)
        val t = normalize(title)
        if (q.isEmpty() || t.isEmpty()) return 0.0
        if (q == t) return 1.0
        val qn = q.replace(" ", "")
        val tn = t.replace(" ", "")
        if (qn == tn) return 0.98
        if (tn.contains(qn) && qn.length >= 4) return 0.9
        if (qn.contains(tn) && tn.length >= 4) return 0.85
        val qw = words(q)
        val tw = words(t)
        if (qw.isEmpty() || tw.isEmpty()) return 0.0
        val found = qw.count { w -> tw.any { close(w, it) } }
        val share = found.toDouble() / qw.size
        // Whole-string similarity catches "more bid" for "Morbid".
        val whole = 1.0 - distance(qn, tn).toDouble() / maxOf(qn.length, tn.length)
        return maxOf(share * 0.8, whole * 0.8)
    }

    /** The best candidate scoring at least [min], or null. */
    fun <T> best(query: String, candidates: List<T>, min: Double = 0.6, title: (T) -> String): T? =
        candidates.map { it to score(query, title(it)) }.filter { it.second >= min }.maxByOrNull { it.second }?.first

    private fun close(a: String, b: String): Boolean {
        if (a == b) return true
        if (a.length >= 4 && b.startsWith(a)) return true
        val d = distance(a, b)
        return if (maxOf(a.length, b.length) <= 4) d == 0 else d <= maxOf(a.length, b.length) / 4
    }

    private fun distance(a: String, b: String): Int {
        val prev = IntArray(b.length + 1) { it }
        val cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            System.arraycopy(cur, 0, prev, 0, cur.size)
        }
        return prev[b.length]
    }
}
