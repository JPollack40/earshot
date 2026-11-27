package com.earshot.app.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.earshot.app.EarshotApp
import com.earshot.app.data.browse.BrowseApi
import com.earshot.app.data.browse.BrowseCard
import com.earshot.app.data.browse.BrowsePage
import com.earshot.app.data.browse.BrowsePrefs
import com.earshot.app.data.browse.HiddenShow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** What a "See all" or category list is showing. */
data class BrowseList(val title: String, val items: List<BrowseCard>?, val error: String? = null)

/** Loading state of a podcast's "You might like" tab. */
data class SimilarState(val items: List<BrowseCard>? = null, val error: String? = null)

/**
 * Phase 13: the Browse page's state on the phone (held by [AppViewModel]). Pages load from the
 * "browse" edge function; "Not interested" hides a card at once and is saved for the account.
 */
class BrowseModel(app: EarshotApp, private val scope: CoroutineScope, private val say: (String) -> Unit) {
    val api = BrowseApi(app)

    var page by mutableStateOf<BrowsePage?>(null)
        private set
    var loading by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    private var loadedAt = 0L
    private var loadJob: Job? = null

    /** Shows hidden while the page is open (so they vanish at once, before the next load). */
    val hiddenNow = mutableStateMapOf<String, Boolean>()

    /** Loads the page, unless one loaded in the last 30 minutes ([force]: always). */
    fun load(force: Boolean = false) {
        if (loading) return
        if (!force && page != null && System.currentTimeMillis() - loadedAt < 30 * 60 * 1000L) return
        loadJob = scope.launch {
            loading = true
            error = null
            try {
                page = api.page()
                loadedAt = System.currentTimeMillis()
                hiddenNow.clear()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error = e.message ?: "Browse didn't load"
            } finally {
                loading = false
            }
        }
    }

    fun notInterested(card: BrowseCard) {
        hiddenNow[card.feedUrl] = true
        scope.launch {
            try {
                api.hide(card)
                say("${card.title} won't be suggested again (undo in Browse settings)")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                hiddenNow.remove(card.feedUrl)
                say("Couldn't hide it: ${e.message}")
            }
        }
    }

    // ---- "See all" and category lists ----

    var list by mutableStateOf<BrowseList?>(null)
        private set

    fun showAll(title: String, items: List<BrowseCard>) {
        list = BrowseList(title, items)
    }

    /** A category's shows (up to 100). */
    fun openCategory(name: String) {
        list = BrowseList(name, null)
        scope.launch {
            list = try {
                BrowseList(name, api.category(name))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                BrowseList(name, emptyList(), e.message ?: "Didn't load")
            }
        }
    }

    var categories by mutableStateOf<List<String>?>(null)
        private set

    fun loadCategories() {
        if (categories != null) return
        scope.launch {
            categories = try {
                api.categories()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Categories didn't load")
                null
            }
        }
    }

    // ---- "You might like" on podcast pages ----

    val similar = mutableStateMapOf<String, SimilarState>()

    fun loadSimilar(feedUrl: String, title: String) {
        if (similar[feedUrl]?.items != null) return
        similar[feedUrl] = SimilarState()
        scope.launch {
            similar[feedUrl] = try {
                SimilarState(api.similar(feedUrl, title))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                SimilarState(emptyList(), e.message ?: "Didn't load")
            }
        }
    }

    // ---- Browse settings ----

    var hidden by mutableStateOf<List<HiddenShow>?>(null)
        private set

    fun loadHidden() {
        scope.launch {
            hidden = try {
                api.hidden()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say(e.message ?: "Didn't load")
                emptyList()
            }
        }
    }

    fun unhide(show: HiddenShow) {
        hidden = hidden?.filterNot { it.feedUrl == show.feedUrl }
        scope.launch {
            try {
                api.unhide(show.feedUrl)
                loadedAt = 0 // reload next time Browse opens
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't undo: ${e.message}")
            }
        }
    }

    /** Saves Browse settings and reloads the page with them. */
    fun savePrefs(p: BrowsePrefs) {
        page = page?.copy(prefs = p)
        scope.launch {
            try {
                api.savePrefs(p)
                loadJob?.cancel()
                loading = false
                load(force = true)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                say("Couldn't save Browse settings: ${e.message}")
            }
        }
    }
}
