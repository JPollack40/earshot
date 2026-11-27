package com.earshot.app.data.transcript

import com.earshot.app.data.EpisodeSearch
import org.json.JSONArray
import org.json.JSONObject

/** One stretch of a transcript. Times in ms from the start of the episode; [speaker] may be null. */
data class Segment(val startMs: Long, val endMs: Long, val text: String, val speaker: String? = null)

/** A parsed transcript: its segments, and whether they carry real times. */
data class ParsedTranscript(val segments: List<Segment>, val timed: Boolean)

/**
 * Transcript formats. Feeds publish Podcasting 2.0 transcripts as WebVTT, SRT, JSON, HTML or plain
 * text; Gemini's come back from the "transcribe" function as segments. Everything is stored in one
 * shared JSON shape (also used by the web player and Supabase):
 *   [{"s": startMs, "e": endMs, "t": "text", "sp": "speaker or null"}]
 */
object TranscriptFormat {
    // ------------------------------------------------------------ shared JSON

    fun toJson(segments: List<Segment>): String {
        val a = JSONArray()
        for (s in segments) {
            a.put(JSONObject().put("s", s.startMs).put("e", s.endMs).put("t", s.text).put("sp", s.speaker ?: JSONObject.NULL))
        }
        return a.toString()
    }

    fun fromJson(json: String): List<Segment> = fromJsonArray(JSONArray(json))

    fun fromJsonArray(a: JSONArray): List<Segment> {
        val out = ArrayList<Segment>(a.length())
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            out += Segment(
                o.optLong("s"),
                o.optLong("e"),
                o.optString("t"),
                if (o.isNull("sp")) null else o.optString("sp").ifBlank { null },
            )
        }
        return out
    }

    /** All the text, lower-cased, with curly quotes made straight: what search looks through. */
    fun plain(segments: List<Segment>): String =
        segments.joinToString("\n") { it.text }.lowercase().replace('’', '\'').replace('‘', '\'').replace('“', '"').replace('”', '"')

    // ------------------------------------------------------------ feed formats

    /** Parses a feed transcript by its declared type, falling back to sniffing the content. */
    fun parse(body: String, type: String?): ParsedTranscript {
        val text = body.removePrefix("﻿")
        val trimmed = text.trimStart()
        val t = type?.lowercase().orEmpty()
        return when {
            t == "text/vtt" || trimmed.startsWith("WEBVTT") -> ParsedTranscript(tidy(parseCues(text)), true)
            t.contains("subrip") || t.contains("srt") -> ParsedTranscript(tidy(parseCues(text)), true)
            t.contains("json") || trimmed.startsWith("{") -> ParsedTranscript(tidy(parseJson(trimmed)), true)
            t.contains("html") || Regex("<(p|br|html|body|div)\\b", RegexOption.IGNORE_CASE).containsMatchIn(text) ->
                ParsedTranscript(parseHtml(text), false)
            text.contains("-->") -> ParsedTranscript(tidy(parseCues(text)), true)
            else -> ParsedTranscript(parsePlain(text), false)
        }
    }

    private val cueTime = Regex("""((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})\s*-->\s*((?:\d+:)?\d{1,2}:\d{2}[.,]\d{1,3})""")
    private val voice = Regex("""<v(?:\.[^ >]*)?\s+([^>]+)>""")
    private val tags = Regex("<[^>]*>")
    private val space = Regex("""\s+""")

    /** "01:02:03.450", "02:03,450" → ms */
    internal fun clock(s: String): Long {
        val parts = s.replace(',', '.').split(':')
        var sec = 0.0
        for (p in parts) sec = sec * 60 + (p.toDoubleOrNull() ?: 0.0)
        return Math.round(sec * 1000)
    }

    /** WebVTT and SRT: each cue is a time line followed by text lines, ended by a blank line. */
    internal fun parseCues(text: String): List<Segment> {
        val out = mutableListOf<Segment>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        var i = 0
        while (i < lines.size) {
            val m = cueTime.find(lines[i])
            if (m == null) {
                i++
                continue
            }
            val start = clock(m.groupValues[1])
            val end = clock(m.groupValues[2])
            i++
            val sb = StringBuilder()
            var speaker: String? = null
            while (i < lines.size && lines[i].isNotBlank() && cueTime.find(lines[i]) == null) {
                val line = lines[i]
                voice.find(line)?.let { speaker = it.groupValues[1].trim() }
                // SRT/VTT speaker prefix "Name: text" is left as is (it's part of the text).
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(decode(tags.replace(line, "")))
                i++
            }
            val cue = space.replace(sb.toString(), " ").trim()
            if (cue.isNotEmpty()) out += Segment(start, end.coerceAtLeast(start), cue, speaker)
        }
        return out
    }

    /** Podcasting 2.0 JSON: {"segments":[{"speaker":"..","startTime":1.2,"endTime":3.4,"body":".."}]} */
    private fun parseJson(text: String): List<Segment> {
        val root = JSONObject(text)
        val a = root.optJSONArray("segments") ?: return emptyList()
        val out = mutableListOf<Segment>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val body = o.optString("body").trim()
            if (body.isEmpty()) continue
            val start = Math.round(o.optDouble("startTime", 0.0) * 1000)
            val end = Math.round(o.optDouble("endTime", o.optDouble("startTime", 0.0)) * 1000)
            out += Segment(start, end.coerceAtLeast(start), decode(body), o.optString("speaker").ifBlank { null })
        }
        return out
    }

    /** HTML transcripts have no times: one untimed segment per paragraph. */
    internal fun parseHtml(text: String): List<Segment> {
        val blocks = text
            .replace(Regex("(?is)<(script|style)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</(p|div|h[1-6]|li|cite)>"), "\n\n")
        return parsePlain(EpisodeSearch.plainText(blocks.replace("\n", "\u0000")).replace("\u0000", "\n"))
    }

    /** Plain text: one untimed segment per paragraph. */
    internal fun parsePlain(text: String): List<Segment> =
        text.replace("\r\n", "\n").split(Regex("\n\\s*\n"))
            .map { space.replace(it, " ").trim() }
            .filter { it.isNotEmpty() }
            .map { Segment(0, 0, it) }

    private fun decode(s: String): String =
        s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")

    /**
     * Joins very short cues (some feeds have one cue per word or per couple of words) into readable
     * segments: same speaker, until a segment is 6 s long and ends a sentence, or reaches 20 s.
     */
    internal fun tidy(segments: List<Segment>): List<Segment> {
        if (segments.isEmpty()) return segments
        val sorted = segments.sortedBy { it.startMs }
        val out = mutableListOf<Segment>()
        var cur = sorted.first()
        for (s in sorted.drop(1)) {
            val len = cur.endMs - cur.startMs
            val sentenceEnd = cur.text.trimEnd().let { it.endsWith('.') || it.endsWith('?') || it.endsWith('!') || it.endsWith('"') }
            val join = s.speaker == cur.speaker && s.startMs - cur.endMs < 1500 &&
                len < 20_000 && !(sentenceEnd && len >= 6_000) && (len < 3_000 || s.endMs - s.startMs < 3_000)
            if (join) {
                cur = Segment(cur.startMs, maxOf(cur.endMs, s.endMs), cur.text + " " + s.text, cur.speaker)
            } else {
                out += cur
                cur = s
            }
        }
        out += cur
        return out
    }

    /** Gemini labels speakers "spk_1", "spk_2": shown as "Speaker 1". Feed names are shown as they are. */
    fun speakerName(sp: String): String =
        Regex("^spk_(\\d+)$").find(sp)?.let { "Speaker " + it.groupValues[1] } ?: sp

    // ------------------------------------------------------------ quotes and search

    /** The transcript text between [startMs] and [endMs] (segments that overlap it), or null. */
    fun quote(segments: List<Segment>, timed: Boolean, startMs: Long, endMs: Long): String? {
        if (!timed) return null
        val parts = segments.filter { it.endMs >= startMs && it.startMs <= endMs }.map { it.text }
        return parts.joinToString(" ").trim().ifEmpty { null }
    }

    /** Index of the segment playing at [positionMs] (the last one starting at or before it), or -1. */
    fun indexAt(segments: List<Segment>, positionMs: Long): Int {
        var lo = 0
        var hi = segments.size - 1
        var ans = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (segments[mid].startMs <= positionMs) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * Segments that match search [terms] (from [EpisodeSearch.terms]): every term must appear in the
     * segment or the one after it (so a phrase split across two segments is still found), and at
     * least one must start in the segment itself.
     */
    fun matchingSegments(segments: List<Segment>, terms: List<String>): List<Int> {
        if (terms.isEmpty()) return emptyList()
        val lower = segments.map { plain(listOf(it)) }
        val hits = mutableListOf<Int>()
        for (i in segments.indices) {
            val window = if (i + 1 < lower.size) lower[i] + " " + lower[i + 1] else lower[i]
            // Every term is in this segment or runs on into the next; at least one starts in this one.
            if (terms.all { window.contains(it) } && terms.any { window.indexOf(it) in 0 until lower[i].length }) hits += i
        }
        return hits
    }
}
