package com.earshot.app.data

import com.earshot.app.data.db.Episode
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * Phase 15: the phone's copies of rules the web player also has, checked against the same cases
 * as web/tests/vectors.test.ts (shared/test-vectors/), so the two can't drift apart.
 */
class SharedVectorsTest {
    private fun vectors(name: String): JSONObject {
        // Unit tests run in android/app; the vectors are at the repository's top level.
        val file = generateSequence(File("").absoluteFile) { it.parentFile }
            .map { File(it, "shared/test-vectors/$name") }
            .first { it.exists() }
        return JSONObject(file.readText())
    }

    private fun JSONArray.strings() = (0 until length()).map { getString(it) }
    private fun JSONArray.objects() = (0 until length()).map { getJSONObject(it) }

    @Test
    fun feedKey() {
        for (c in vectors("feedKey.json").getJSONArray("cases").objects()) {
            val same = Repository.feedKey(c.getString("a")) == Repository.feedKey(c.getString("b"))
            assertEquals("${c.getString("a")} vs ${c.getString("b")}", c.getBoolean("same"), same)
        }
    }

    @Test
    fun keywords() {
        val v = vectors("keywords.json")
        for (c in v.getJSONArray("normalize").objects()) assertEquals(c.getString("out"), Automation.normalize(c.getString("in")))
        for (c in v.getJSONArray("words").objects()) assertEquals(c.getJSONArray("out").strings(), Automation.words(c.getString("in")))
    }

    @Test
    fun markRange() {
        val v = vectors("markRange.json")
        val podcastIds = mutableMapOf<String, Long>()
        val episodes = v.getJSONArray("episodes").objects().map { o ->
            Episode(
                id = o.getString("id"),
                podcastId = podcastIds.getOrPut(o.getString("podcast")) { podcastIds.size + 1L },
                guid = o.getString("id"),
                title = o.getString("id"),
                pubDate = o.getLong("pub"),
                audioUrl = "https://example.com/${o.getString("id")}.mp3",
                played = o.optBoolean("played"),
                queuePosition = if (o.has("queue")) o.getLong("queue") else null,
            )
        }
        for (c in v.getJSONArray("cases").objects()) {
            val anchor = episodes.first { it.id == c.getString("anchor") }
            val direction = if (c.getString("direction") == "older") MarkRange.Direction.OLDER else MarkRange.Direction.NEWER
            val loaded = if (c.isNull("loaded")) null else c.getString("loaded")
            val r = MarkRange.pick(episodes, anchor, direction, loaded)
            val label = "${c.getString("anchor")} ${c.getString("direction")}"
            assertEquals(label, c.getJSONArray("ids").strings(), r.ids)
            assertEquals(label, c.getInt("skipped"), r.skipped)
        }
    }

    @Test
    fun smartPlaylists() {
        val v = vectors("playlists.json")
        val subscribed = v.getJSONArray("subscribed").strings().toSet()
        // The phone's database query already limits candidates to subscribed podcasts.
        val candidates = v.getJSONArray("episodes").objects()
            .filter { it.getString("feed") in subscribed }
            .map { o ->
                PlaylistCandidate(
                    id = o.getString("id"),
                    feedKey = Repository.feedKey(o.getString("feed")),
                    title = o.getString("title"),
                    pubDate = o.getLong("pub"),
                    durationMs = o.getLong("durationMs"),
                    positionMs = o.getLong("positionMs"),
                    played = o.optBoolean("played"),
                    downloaded = o.optBoolean("downloaded"),
                )
            }
        for (c in v.getJSONArray("cases").objects()) {
            val filters = PlaylistJson.parse(c.getJSONObject("filters").toString())
            val ids = SmartPlaylists.select(filters, candidates, v.getLong("now"), Repository::feedKey)
            assertEquals(c.getString("name"), c.getJSONArray("ids").strings(), ids)
        }
    }
}
