package com.earshot.app.data.feed

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.InputStream

data class OpmlFeed(val title: String, val url: String)

/** Reads the subscription export (OPML) that every podcast app can produce. */
object OpmlParser {
    fun parse(input: InputStream): List<OpmlFeed> {
        val p = Xml.newPullParser()
        p.setInput(input, null)
        val feeds = mutableListOf<OpmlFeed>()
        var event = p.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            if (event == XmlPullParser.START_TAG && p.name.equals("outline", ignoreCase = true)) {
                val url = p.getAttributeValue(null, "xmlUrl")?.trim()
                if (!url.isNullOrEmpty()) {
                    val title = p.getAttributeValue(null, "text")
                        ?: p.getAttributeValue(null, "title")
                        ?: url
                    feeds += OpmlFeed(title, url)
                }
            }
            event = p.next()
        }
        return feeds.distinctBy { it.url }
    }
}
