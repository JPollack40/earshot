package com.earshot.app.data.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Locale

data class ParsedFeed(
    val title: String,
    val author: String,
    val description: String,
    val imageUrl: String?,
    val episodes: List<ParsedEpisode>,
    // Podcasting 2.0 extras (phase 10)
    val fundingUrl: String? = null,
    val fundingText: String? = null,
    val persons: List<Person> = emptyList(),
    /** podcast:updateFrequency complete="true" or itunes:complete "Yes": no new episodes will come. */
    val complete: Boolean = false,
    /** Phase 20 (B3): itunes:type "serial", a show meant to be heard in order. */
    val serial: Boolean = false,
)

/** A podcast:person: host, guest and so on. [role] is lower-case ("host" when the feed doesn't say). */
data class Person(val name: String, val role: String, val imageUrl: String?, val href: String?)

data class ParsedEpisode(
    val guid: String,
    val title: String,
    val description: String,
    val pubDate: Long,
    val audioUrl: String,
    val mimeType: String?,
    val durationMs: Long,
    val imageUrl: String?,
    val chaptersUrl: String?,
    val transcriptUrl: String?,
    val transcriptType: String?,
    val season: Int? = null,
    val episodeNo: Double? = null,
    val persons: List<Person> = emptyList(),
)

/** Streaming RSS parser covering plain RSS 2.0, iTunes tags and the Podcasting 2.0 namespace. */
object FeedParser {
    private const val NS_ITUNES = "http://www.itunes.com/dtds/podcast-1.0.dtd"
    private const val NS_PODCAST = "https://podcastindex.org/namespace/1.0"
    private const val NS_CONTENT = "http://purl.org/rss/1.0/modules/content/"

    /**
     * Feeds declare the Podcasting 2.0 namespace under more than one URI. The canonical one is
     * NS_PODCAST, but older feeds (including the "Podcasting 2.0" show's own feed) still use the
     * GitHub docs URL, so normalize every known spelling to NS_PODCAST.
     */
    private fun normalizeNamespace(ns: String): String {
        val n = ns.trim().trimEnd('/').lowercase()
        return when {
            n.endsWith("podcastindex.org/namespace/1.0") -> NS_PODCAST
            n.contains("github.com/podcastindex-org/podcast-namespace") -> NS_PODCAST
            else -> ns
        }
    }

    private class ItemBuilder {
        var guid: String? = null
        var title = ""
        var description = ""
        var contentEncoded = ""
        var summary = ""
        var pubDate = 0L
        var audioUrl: String? = null
        var mimeType: String? = null
        var durationMs = 0L
        var imageUrl: String? = null
        var chaptersUrl: String? = null
        var transcriptUrl: String? = null
        var transcriptType: String? = null
        var transcriptRank = 0
        var season: Int? = null
        var episodeNo: Double? = null
        val persons = mutableListOf<Person>()

        fun build(): ParsedEpisode? {
            val url = audioUrl ?: return null
            val desc = contentEncoded.ifBlank { description.ifBlank { summary } }
            return ParsedEpisode(
                guid = guid?.takeIf { it.isNotBlank() } ?: url,
                title = title.ifBlank { "Untitled episode" },
                description = desc,
                pubDate = pubDate,
                audioUrl = url,
                mimeType = mimeType,
                durationMs = durationMs,
                imageUrl = imageUrl,
                chaptersUrl = chaptersUrl,
                transcriptUrl = transcriptUrl,
                transcriptType = transcriptType,
                season = season,
                episodeNo = episodeNo,
                persons = persons.toList(),
            )
        }
    }

    fun parse(input: InputStream): ParsedFeed {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, null)

        var title = ""
        var author = ""
        var description = ""
        var summary = ""
        var itunesImage: String? = null
        var rssImage: String? = null
        var inChannelImage = false
        var fundingUrl: String? = null
        var fundingText: String? = null
        val persons = mutableListOf<Person>()
        var complete = false
        var serial = false
        var item: ItemBuilder? = null
        val episodes = mutableListOf<ParsedEpisode>()

        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG) {
                val ns = normalizeNamespace(p.namespace ?: "")
                val name = p.name
                val cur = item
                if (cur != null) {
                    when (ns) {
                        "" -> when (name) {
                            "title" -> cur.title = readText(p)
                            "guid" -> cur.guid = readText(p)
                            "pubDate" -> cur.pubDate = parseDate(readText(p))
                            "description" -> cur.description = readText(p)
                            "enclosure" -> if (cur.audioUrl == null) {
                                cur.audioUrl = p.getAttributeValue(null, "url")?.trim()?.takeIf { it.isNotEmpty() }
                                cur.mimeType = p.getAttributeValue(null, "type")
                            }
                        }
                        NS_CONTENT -> if (name == "encoded") cur.contentEncoded = readText(p)
                        NS_ITUNES -> when (name) {
                            "duration" -> cur.durationMs = parseDuration(readText(p))
                            "image" -> cur.imageUrl = p.getAttributeValue(null, "href")
                            "summary" -> cur.summary = readText(p)
                            // podcast:season / podcast:episode win when both are present.
                            "season" -> readText(p).toIntOrNull()?.let { if (cur.season == null) cur.season = it }
                            "episode" -> readText(p).toDoubleOrNull()?.let { if (cur.episodeNo == null) cur.episodeNo = it }
                        }
                        NS_PODCAST -> when (name) {
                            "season" -> readText(p).trim().toIntOrNull()?.let { cur.season = it }
                            "episode" -> readText(p).trim().toDoubleOrNull()?.let { cur.episodeNo = it }
                            "person" -> readPerson(p)?.let { cur.persons += it }
                            "chapters" -> cur.chaptersUrl = p.getAttributeValue(null, "url")
                            "transcript" -> {
                                val url = p.getAttributeValue(null, "url")
                                val type = p.getAttributeValue(null, "type")
                                val rank = transcriptRank(type)
                                if (url != null && rank > cur.transcriptRank) {
                                    cur.transcriptUrl = url
                                    cur.transcriptType = type
                                    cur.transcriptRank = rank
                                }
                            }
                        }
                    }
                } else {
                    when (ns) {
                        "" -> when (name) {
                            "item" -> item = ItemBuilder()
                            "image" -> inChannelImage = true
                            "url" -> if (inChannelImage) rssImage = readText(p)
                            "title" -> if (!inChannelImage && title.isEmpty()) title = readText(p)
                            "description" -> if (description.isEmpty()) description = readText(p)
                        }
                        NS_ITUNES -> when (name) {
                            "author" -> author = readText(p)
                            "image" -> itunesImage = p.getAttributeValue(null, "href")
                            "summary" -> summary = readText(p)
                            "complete" -> if (readText(p).equals("yes", ignoreCase = true)) complete = true
                            "type" -> serial = readText(p).equals("serial", ignoreCase = true)
                        }
                        NS_PODCAST -> when (name) {
                            "funding" -> if (fundingUrl == null) {
                                fundingUrl = p.getAttributeValue(null, "url")?.trim()?.takeIf { it.startsWith("http") }
                                fundingText = readText(p).ifBlank { null }
                            }
                            "person" -> readPerson(p)?.let { persons += it }
                            "updateFrequency" -> if (p.getAttributeValue(null, "complete")?.trim().equals("true", ignoreCase = true)) complete = true
                        }
                    }
                }
            } else if (event == XmlPullParser.END_TAG && p.namespace.isNullOrEmpty()) {
                when (p.name) {
                    "item" -> {
                        item?.build()?.let { episodes.add(it) }
                        item = null
                    }
                    "image" -> if (item == null) inChannelImage = false
                }
            }
            event = p.next()
        }

        return ParsedFeed(
            title = title,
            author = author,
            description = description.ifBlank { summary },
            imageUrl = itunesImage?.takeIf { it.isNotBlank() } ?: rssImage,
            episodes = episodes,
            fundingUrl = fundingUrl,
            fundingText = fundingText,
            persons = persons,
            complete = complete,
            serial = serial,
        )
    }

    private fun readPerson(p: XmlPullParser): Person? {
        val role = p.getAttributeValue(null, "role")?.trim()?.lowercase()?.ifBlank { null } ?: "host"
        val img = p.getAttributeValue(null, "img")?.trim()?.takeIf { it.startsWith("http") }
        val href = p.getAttributeValue(null, "href")?.trim()?.takeIf { it.startsWith("http") }
        val name = readText(p).ifBlank { return null }
        return Person(name, role, img, href)
    }

    /** Reads all text inside the current element, skipping any nested tags. Leaves the parser on the element's end tag. */
    private fun readText(p: XmlPullParser): String {
        val sb = StringBuilder()
        var depth = 1
        while (depth > 0) {
            when (p.next()) {
                XmlPullParser.TEXT -> sb.append(p.text)
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return sb.toString().trim()
            }
        }
        return sb.toString().trim()
    }

    private fun transcriptRank(type: String?): Int = when (type?.lowercase()) {
        "text/vtt" -> 4
        "application/x-subrip", "application/srt" -> 3
        "application/json" -> 2
        null -> 0
        else -> 1
    }

    internal fun parseDuration(raw: String): Long {
        val s = raw.trim()
        if (s.isEmpty()) return 0
        return try {
            val parts = s.split(":")
            var seconds = 0.0
            for (part in parts) seconds = seconds * 60 + part.trim().toDouble()
            (seconds * 1000).toLong()
        } catch (e: NumberFormatException) {
            0
        }
    }

    private val DATE_FORMATS = listOf(
        "EEE, d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm:ss zzz",
        "d MMM yyyy HH:mm:ss Z",
        "EEE, d MMM yyyy HH:mm Z",
        "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
        "EEE, d MMM yyyy",
    )

    internal fun parseDate(raw: String): Long {
        val s = raw.trim().replace(Regex("\\s+"), " ")
        if (s.isEmpty()) return 0
        for (pattern in DATE_FORMATS) {
            val parsed = runCatching { SimpleDateFormat(pattern, Locale.US).parse(s) }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0
    }
}
