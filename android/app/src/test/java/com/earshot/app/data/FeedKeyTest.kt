package com.earshot.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class FeedKeyTest {
    @Test
    fun sameFeedListedDifferently() {
        // Behind the Bastards: as stored in the library vs as Podcast Index lists it.
        val library = "https://www.omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/e5f91208-cc7e-4726-a312-ae280140ad11/d64f756d-6d5e-4fae-b24f-ae280140ad36/podcast.rss"
        val index = "https://omnycontent.com/d/playlist/e73c998e-6e60-432f-8610-ae210140c5b1/E5F91208-CC7E-4726-A312-AE280140AD11/D64F756D-6D5E-4FAE-B24F-AE280140AD36/podcast.rss"
        assertEquals(Repository.feedKey(library), Repository.feedKey(index))
        assertEquals(Repository.feedKey("http://feed.20k.org/"), Repository.feedKey("https://feed.20k.org"))
    }

    @Test
    fun differentFeedsStayDifferent() =
        assertNotEquals(Repository.feedKey("https://feeds.simplecast.com/BqbsxVfO"), Repository.feedKey("https://feeds.simplecast.com/6owr3EpB"))
}
