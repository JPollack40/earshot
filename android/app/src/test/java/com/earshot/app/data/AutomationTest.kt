package com.earshot.app.data

import com.earshot.app.data.Automation.InboxEntry
import com.earshot.app.data.Automation.NewEpisodeAction
import com.earshot.app.data.db.Podcast
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AutomationTest {
    private val min = 60_000L
    private val day = 24L * 60 * 60 * 1000
    private fun podcast() = Podcast(id = 1, feedUrl = "https://example.com/feed", title = "Example")

    @Test
    fun noRulesMeansNewEpisodes() {
        assertEquals(NewEpisodeAction.INBOX, Automation.actionFor(podcast(), "Anything", 40 * min))
        assertFalse(Automation.hasRules(podcast()))
    }

    @Test
    fun actionIsFollowedWhenFiltersPass() {
        val p = podcast().copy(newEpisodeAction = "queue_last")
        assertEquals(NewEpisodeAction.QUEUE_LAST, Automation.actionFor(p, "Episode 12", 40 * min))
        assertTrue(Automation.hasRules(p))
    }

    @Test
    fun excludeWordsSkip() {
        val p = podcast().copy(newEpisodeAction = "queue_next", excludeWords = "bonus, Trailer")
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "BONUS: behind the scenes", 10 * min))
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "Season 3 trailer", 2 * min))
        assertEquals(NewEpisodeAction.QUEUE_NEXT, Automation.actionFor(p, "The octopus episode", 50 * min))
    }

    @Test
    fun includeWordsNeedOneMatch() {
        val p = podcast().copy(includeWords = "part 1,interview")
        assertEquals(NewEpisodeAction.INBOX, Automation.actionFor(p, "The Sinking: Part 1", 60 * min))
        assertEquals(NewEpisodeAction.INBOX, Automation.actionFor(p, "An Interview with Someone", 60 * min))
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "The Sinking: Part 2", 60 * min))
    }

    @Test
    fun curlyApostrophesMatchStraightOnes() {
        val p = podcast().copy(excludeWords = "don't")
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "Why we don’t sleep", 30 * min))
    }

    @Test
    fun lengthFiltersSkipUnknownLengthNever() {
        val p = podcast().copy(minMinutes = 20, maxMinutes = 90)
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "Short", 5 * min))
        assertEquals(NewEpisodeAction.SKIP, Automation.actionFor(p, "Long", 120 * min))
        assertEquals(NewEpisodeAction.INBOX, Automation.actionFor(p, "Just right", 45 * min))
        assertEquals(NewEpisodeAction.INBOX, Automation.actionFor(p, "Unknown length", 0))
    }

    @Test
    fun tidyArchivesOldAndBeyondKeep() {
        val now = 100 * day
        val inbox = listOf(
            InboxEntry("a1", 1, now - 1 * day),
            InboxEntry("a2", 1, now - 2 * day),
            InboxEntry("a3", 1, now - 3 * day),
            InboxEntry("b1", 2, now - 20 * day),
            InboxEntry("b2", 2, now - 5 * day),
            InboxEntry("c1", 3, 0), // unknown release date: never archived by age
        )
        // Podcast 1 keeps its latest 2; everyone else archives after 14 days.
        val out = Automation.toArchive(inbox, now, days = { if (it == 1L) 0 else 14 }, keep = { if (it == 1L) 2 else 0 })
        assertEquals(setOf("a3", "b1"), out)
    }

    @Test
    fun tidyOffArchivesNothing() {
        val now = 100 * day
        val inbox = listOf(InboxEntry("a", 1, now - 90 * day), InboxEntry("b", 1, now - 80 * day))
        assertTrue(Automation.toArchive(inbox, now, days = { 0 }, keep = { 0 }).isEmpty())
    }
}
