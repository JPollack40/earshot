package com.earshot.app.data.feed

import org.json.JSONArray
import org.json.JSONObject

/** podcast:person lists stored as JSON in Room: [{"name","role","img","href"}]. */
object Persons {
    fun toJson(list: List<Person>): String? {
        if (list.isEmpty()) return null
        val a = JSONArray()
        for (p in list.distinctBy { it.name.lowercase() to it.role }) {
            a.put(
                JSONObject().put("name", p.name).put("role", p.role)
                    .put("img", p.imageUrl ?: JSONObject.NULL).put("href", p.href ?: JSONObject.NULL),
            )
        }
        return a.toString()
    }

    fun parse(json: String?): List<Person> {
        if (json.isNullOrBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(json)
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                Person(
                    name = o.optString("name").ifBlank { return@mapNotNull null },
                    role = o.optString("role", "host"),
                    imageUrl = o.optString("img").takeIf { !o.isNull("img") && it.isNotBlank() },
                    href = o.optString("href").takeIf { !o.isNull("href") && it.isNotBlank() },
                )
            }
        }.getOrDefault(emptyList())
    }

    /** "Hosts: A, B · Guest: C" for an episode (its own people, else the podcast's). */
    fun summary(list: List<Person>): String = list.groupBy { it.role }.entries
        .sortedBy { if (it.key == "host") 0 else if (it.key == "guest") 1 else 2 }
        .joinToString(" · ") { (role, people) ->
            val label = role.replaceFirstChar { it.uppercase() } + if (people.size > 1) "s" else ""
            "$label: " + people.joinToString(", ") { it.name }
        }
}

/** "S2 E14", "E14" or null: the badge for an episode. Whole numbers show without ".0". */
fun episodeBadge(season: Int?, episode: Double?): String? {
    val e = episode?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() }
    return when {
        season != null && e != null -> "S$season E$e"
        e != null -> "E$e"
        season != null -> "S$season"
        else -> null
    }
}
