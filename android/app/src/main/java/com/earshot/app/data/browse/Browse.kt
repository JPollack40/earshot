package com.earshot.app.data.browse

import com.earshot.app.EarshotApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** A podcast suggested by Browse. Tapping it opens the usual preview. */
data class BrowseCard(
    val feedUrl: String,
    val title: String,
    val author: String,
    val artworkUrl: String?,
    /** Why it's shown: "Recommended by Radiolab", "Popular in History", "#3 on Apple Podcasts". */
    val reason: String,
)

/** One row of the Browse page. [group] is what Browse settings hide and reorder. */
data class BrowseSection(
    val id: String,
    val group: String,
    val title: String,
    val subtitle: String?,
    val items: List<BrowseCard>,
    /** For category rows: the category, so "See all" can ask for more. */
    val category: String?,
)

/** Browse settings, one row per account on the server (browse_prefs, 007_browse.sql). */
data class BrowsePrefs(
    val country: String = "us",
    val categories: List<String> = DEFAULT_CATEGORIES,
    val hiddenSections: List<String> = emptyList(),
    val sectionOrder: List<String> = emptyList(),
    val friendsOptIn: Boolean = false,
) {
    companion object {
        val DEFAULT_CATEGORIES = listOf("News", "Music", "History", "Comedy", "True Crime", "Science", "Technology", "Sports")
    }
}

data class BrowsePage(
    val sections: List<BrowseSection>,
    val prefs: BrowsePrefs,
    /** Section groups in the order shown (for Browse settings). */
    val groups: List<String>,
    val hiddenCount: Int,
    val notes: List<String>,
)

data class HiddenShow(val feedUrl: String, val title: String, val imageUrl: String?)

/**
 * Phase 13: Browse Podcasts. The "browse" edge function builds the page for the signed-in account (from
 * its own listening, podrolls, Podcast Index and Apple's chart); settings and "Not interested" are read
 * and written here directly. Online only; nothing is stored on the phone.
 */
class BrowseApi(private val app: EarshotApp) {
    private val supabase get() = app.sync.supabase

    private suspend fun call(body: JSONObject): JSONObject {
        if (!app.sync.canSync) throw IllegalStateException("Sign in (Settings > Sync) to browse podcasts")
        val res = supabase.function("browse", body, readTimeoutMs = 120_000)
        if (res.has("error")) throw IllegalStateException(res.optString("error"))
        return res
    }

    suspend fun page(): BrowsePage = withContext(Dispatchers.Default) {
        val o = call(JSONObject().put("mode", "page"))
        BrowsePage(
            sections = o.optJSONArray("sections").objects().map { s ->
                BrowseSection(
                    id = s.optString("id"),
                    group = s.optString("group"),
                    title = s.optString("title"),
                    subtitle = s.optString("subtitle").ifBlank { null },
                    items = cards(s.optJSONArray("items")),
                    category = s.optString("category").ifBlank { null },
                )
            },
            prefs = prefsOf(o.optJSONObject("prefs") ?: JSONObject()),
            groups = o.optJSONArray("groups").strings(),
            hiddenCount = o.optInt("hiddenCount"),
            notes = o.optJSONArray("notes").strings(),
        )
    }

    suspend fun category(name: String): List<BrowseCard> =
        cards(call(JSONObject().put("mode", "category").put("category", name)).optJSONArray("items"))

    suspend fun categories(): List<String> = call(JSONObject().put("mode", "categories")).optJSONArray("categories").strings()

    /** "You might like" for one podcast. */
    suspend fun similar(feedUrl: String, title: String): List<BrowseCard> =
        cards(call(JSONObject().put("mode", "similar").put("feedUrl", feedUrl).put("title", title)).optJSONArray("items"))

    suspend fun hide(card: BrowseCard) {
        supabase.upsert(
            "browse_hidden",
            "user_id,feed_url",
            JSONArray().put(JSONObject().put("feed_url", card.feedUrl).put("title", card.title).put("image_url", card.artworkUrl ?: JSONObject.NULL)),
        )
    }

    suspend fun unhide(feedUrl: String) {
        supabase.delete("browse_hidden?feed_url=eq.${java.net.URLEncoder.encode(feedUrl, "UTF-8")}")
    }

    suspend fun hidden(): List<HiddenShow> =
        supabase.select("browse_hidden?select=feed_url,title,image_url&order=created_at.desc").objects().map {
            HiddenShow(it.optString("feed_url"), it.optString("title"), it.optString("image_url").ifBlank { null }.takeIf { v -> v != "null" })
        }

    suspend fun savePrefs(p: BrowsePrefs) {
        supabase.upsert(
            "browse_prefs",
            "user_id",
            JSONArray().put(
                JSONObject()
                    .put("country", p.country)
                    .put("categories", JSONArray(p.categories))
                    .put("hidden_sections", JSONArray(p.hiddenSections))
                    .put("section_order", JSONArray(p.sectionOrder))
                    .put("friends_opt_in", p.friendsOptIn)
                    .put("updated_at", java.time.Instant.now().toString()),
            ),
        )
    }

    companion object {
        /** Section groups and their names in Browse settings. */
        val GROUP_NAMES = linkedMapOf(
            "for_you" to "You may be into",
            "because" to "Because you listen to…",
            "friends" to "Popular with your friends",
            "top" to "Top podcasts (Apple)",
            "trending" to "Trending",
            "your_categories" to "Your top categories",
            "categories" to "Category rows",
        )

        /** Countries offered for the chart (Apple storefront codes). */
        val COUNTRIES = linkedMapOf(
            "us" to "United States", "gb" to "United Kingdom", "ca" to "Canada", "au" to "Australia", "ie" to "Ireland",
            "nz" to "New Zealand", "in" to "India", "za" to "South Africa", "de" to "Germany", "fr" to "France",
            "es" to "Spain", "it" to "Italy", "nl" to "Netherlands", "se" to "Sweden", "br" to "Brazil", "mx" to "Mexico", "jp" to "Japan",
        )

        private fun JSONArray?.objects(): List<JSONObject> =
            if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

        private fun cards(a: JSONArray?): List<BrowseCard> = a.objects().mapNotNull { c ->
            val url = c.optString("feedUrl")
            if (url.isBlank()) null
            else BrowseCard(
                feedUrl = url,
                title = c.optString("title"),
                author = c.optString("author"),
                artworkUrl = c.optString("artworkUrl").takeIf { it.isNotBlank() && it != "null" },
                reason = c.optString("reason"),
            )
        }

        private fun prefsOf(o: JSONObject) = BrowsePrefs(
            country = o.optString("country").ifBlank { "us" },
            categories = o.optJSONArray("categories").strings().ifEmpty { BrowsePrefs.DEFAULT_CATEGORIES },
            hiddenSections = o.optJSONArray("hidden_sections").strings(),
            sectionOrder = o.optJSONArray("section_order").strings(),
            friendsOptIn = o.optBoolean("friends_opt_in"),
        )
    }
}
