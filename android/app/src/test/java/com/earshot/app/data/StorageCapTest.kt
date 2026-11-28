package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageCapTest {
    private val now = 100L * 24 * 3600 * 1000
    private val old = now - 3 * 24 * 3600 * 1000L
    private fun item(id: String, gb: Double, keep: Boolean = false, used: Long = old, downloaded: Long = old) =
        StorageCap.Item(id, (gb * StorageCap.GB).toLong(), keep, used, downloaded)

    @Test fun noLimitOrUnderItDeletesNothing() {
        val items = listOf(item("a", 1.0), item("b", 1.0))
        assertEquals(emptyList<String>(), StorageCap.toDelete(items, 0, null, now))
        assertEquals(emptyList<String>(), StorageCap.toDelete(items, 3 * StorageCap.GB, null, now))
    }

    @Test fun oldestUnqueuedGoFirstUntilItFits() {
        val items = listOf(
            item("queued", 1.0, keep = true, used = old - 1000),
            item("newer", 1.0, used = old + 5000),
            item("oldest", 1.0, used = old),
        )
        assertEquals(listOf("oldest"), StorageCap.toDelete(items, 2 * StorageCap.GB, null, now))
        assertEquals(listOf("oldest", "newer"), StorageCap.toDelete(items, 1 * StorageCap.GB, null, now))
    }

    @Test fun queuedPlayingAndJustDownloadedAreKept() {
        val items = listOf(
            item("queued", 2.0, keep = true),
            item("playing", 2.0),
            item("fresh", 2.0, downloaded = now - 60_000, used = now - 60_000),
        )
        assertEquals(emptyList<String>(), StorageCap.toDelete(items, 1 * StorageCap.GB, "playing", now))
    }
}
