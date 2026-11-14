package com.example.careway

import com.example.careway.core.CareEvent
import com.example.careway.data.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class CareReportTest {
    @Test fun csvExportsThirtyDaysAndKeepsMissingMeasurementEmpty() {
        val now = Instant.parse("2026-11-13T10:00:00Z").toEpochMilli()
        val records = listOf(ReactionRecord(now, null, true, false), ReactionRecord(now, 400, false, true))
        val report = CareReports.summarize(records, emptyList(), false, now, ZoneId.of("Asia/Seoul"))
        val lines = CareReports.csv(report, false).trim().lines()
        assertEquals(31, lines.size)
        assertTrue(lines.last().endsWith(",1,,0"))
        assertTrue(lines.drop(1).all { it.startsWith("vehicle,") })
        assertFalse(CareReports.csv(report, false).contains("400"))
    }

    @Test fun invalidDurationsDoNotDistortAverage() {
        val now = System.currentTimeMillis()
        val report = CareReports.summarize(listOf(ReactionRecord(now, -1, false, false),
            ReactionRecord(now, 60_000, false, false), ReactionRecord(now, 500, false, false)), emptyList(), false, now)
        assertEquals(500L, report.averageMs)
    }
    @Test fun `report keeps demo separate excludes older future records and preserves unanswered attempts`() {
        val now = Instant.parse("2026-11-09T10:00:00Z").toEpochMilli()
        val day = 86_400_000L
        val records = listOf(
            ReactionRecord(now, 600, false, false), ReactionRecord(now - day, 800, false, false),
            ReactionRecord(now - day, null, true, false), ReactionRecord(now - day, 2_000, true, true),
            ReactionRecord(now - 31 * day, 5_000, true, false), ReactionRecord(now + day, 5_000, true, false))
        val events = listOf(CareEvent(now, "전조등을 켜주세요", "", false), CareEvent(now, "전조등을 켜주세요", "", true))
        val report = CareReports.summarize(records, events, false, now, ZoneId.of("Asia/Seoul"))
        assertEquals(30, report.days.size)
        assertEquals(3, report.attempts)
        assertEquals(700L, report.averageMs)
        assertEquals(1, report.headlightWarnings)
    }
}
