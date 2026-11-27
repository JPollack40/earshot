package com.earshot.app.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * A playlist's filters as JSON, the same shape the web player uses (web/src/playlists.ts):
 * {"podcasts":[feed urls],"status":"unplayed","downloaded":false,"minMinutes":null,
 *  "maxMinutes":30,"withinDays":null,"keywords":"","sort":"newest","limit":100}
 */
object PlaylistJson {
    fun toJson(f: PlaylistFilters): JSONObject = JSONObject()
        .put("podcasts", JSONArray(f.podcasts))
        .put("status", f.status.key)
        .put("downloaded", f.downloaded)
        .put("minMinutes", f.minMinutes ?: JSONObject.NULL)
        .put("maxMinutes", f.maxMinutes ?: JSONObject.NULL)
        .put("withinDays", f.withinDays ?: JSONObject.NULL)
        .put("keywords", f.keywords)
        .put("sort", f.sort.key)
        .put("limit", f.limit)

    fun toText(f: PlaylistFilters): String = toJson(f).toString()

    fun parse(text: String?): PlaylistFilters = try {
        parse(JSONObject(text ?: "{}"))
    } catch (e: Exception) {
        PlaylistFilters()
    }

    fun parse(o: JSONObject): PlaylistFilters {
        val list = o.optJSONArray("podcasts")
        return PlaylistFilters(
            podcasts = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optString(it).ifBlank { null } },
            status = PlaylistFilters.Status.of(o.optString("status")),
            downloaded = o.optBoolean("downloaded", false),
            minMinutes = o.optIntOrNull("minMinutes"),
            maxMinutes = o.optIntOrNull("maxMinutes"),
            withinDays = o.optIntOrNull("withinDays"),
            keywords = o.optString("keywords", ""),
            sort = PlaylistFilters.Sort.of(o.optString("sort")),
            limit = o.optInt("limit", 100).coerceIn(1, 500),
        )
    }

    private fun JSONObject.optIntOrNull(name: String): Int? =
        if (!has(name) || isNull(name)) null else optInt(name, 0).takeIf { it > 0 }
}
