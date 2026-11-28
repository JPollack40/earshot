package com.earshot.app.data

/**
 * Phase 17: a limit on the space downloads take (Settings > Downloads > Storage limit). When downloads go
 * over it, the ones you're least likely to need go first: anything not in the queue (or already played),
 * least recently downloaded or played first. Unplayed episodes in the queue are never deleted for space,
 * and the episode playing now is never touched. While over the limit, queued episodes aren't downloaded
 * automatically; a Download you tap always runs.
 */
object StorageCap {
    /** The limits offered, in GB. 0 = no limit. */
    val CHOICES_GB = listOf(0, 1, 2, 5, 10, 20, 50)

    const val GB = 1024L * 1024 * 1024

    /** Just finished downloads are left alone for this long, so a batch you just asked for isn't undone. */
    const val FRESH_MS = 10 * 60 * 1000L

    data class Item(val id: String, val bytes: Long, val keep: Boolean, val lastUsedAt: Long, val downloadedAt: Long)

    fun label(gb: Int) = if (gb == 0) "No limit" else "$gb GB"

    /** Which downloads to delete so [items] fit in [capBytes]. Empty when there's no limit or they fit. */
    fun toDelete(items: List<Item>, capBytes: Long, protectedId: String?, now: Long): List<String> {
        if (capBytes <= 0) return emptyList()
        var total = items.sumOf { it.bytes }
        if (total <= capBytes) return emptyList()
        val out = mutableListOf<String>()
        for (i in items.filter { !it.keep && it.id != protectedId && now - it.downloadedAt >= FRESH_MS }.sortedBy { it.lastUsedAt }) {
            if (total <= capBytes) break
            out += i.id
            total -= i.bytes
        }
        return out
    }
}
