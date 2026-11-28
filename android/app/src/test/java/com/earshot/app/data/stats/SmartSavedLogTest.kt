package com.earshot.app.data.stats

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class SmartSavedLogTest {
    private val zone = ZoneId.of("America/New_York")
    private fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()

    @Test fun addsUpPerDayAndSumsARange() {
        val d1 = LocalDate.of(2026, 11, 27)
        val d2 = d1.plusDays(1)
        var j = SmartSavedLog.add(null, d1, 60_000)
        j = SmartSavedLog.add(j, d1, 30_000)
        j = SmartSavedLog.add(j, d2, 15_000)
        assertEquals(105_000L, SmartSavedLog.between(j, ms(d1), ms(d2.plusDays(1)), zone))
        assertEquals(15_000L, SmartSavedLog.between(j, ms(d2), ms(d2.plusDays(1)), zone))
        assertEquals(0L, SmartSavedLog.between(j, ms(d2.plusDays(1)), ms(d2.plusDays(5)), zone))
    }

    @Test fun dropsVeryOldDays() {
        val old = LocalDate.of(2025, 1, 1)
        val j = SmartSavedLog.add(SmartSavedLog.add(null, old, 1_000), old.plusDays(500), 2_000)
        assertEquals(2_000L, SmartSavedLog.between(j, ms(old), ms(old.plusDays(600)), zone))
    }

    @Test fun badJsonIsEmpty() {
        assertEquals(0L, SmartSavedLog.between("nope", 0, ms(LocalDate.of(2100, 1, 1)), zone))
    }
}
