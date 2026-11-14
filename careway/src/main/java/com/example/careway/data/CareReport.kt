package com.example.careway.data

import com.example.careway.core.CareEvent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class DayReport(val day: LocalDate, val attempts: Int, val averageMs: Long?, val headlightWarnings: Int)
data class CareReport(val days: List<DayReport>, val attempts: Int, val averageMs: Long?, val headlightWarnings: Int)

/** Descriptive telemetry only; does not infer cognitive health or insurance eligibility. */
object CareReports {
fun summarize(records: List<ReactionRecord>, events: List<CareEvent>, demo: Boolean,
               nowMs: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): CareReport {
    val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
    val start = today.minusDays(29)
    fun day(at: Long) = Instant.ofEpochMilli(at).atZone(zone).toLocalDate()
    val r = records.filter { it.demo == demo && it.atMs <= nowMs && day(it.atMs) >= start }
    val e = events.filter { it.demo == demo && it.atMs <= nowMs && day(it.atMs) >= start && it.title == "전조등을 켜주세요" }
    val days = (0L..29L).map { offset ->
        val date = start.plusDays(offset)
        val samples = r.filter { day(it.atMs) == date }
        DayReport(date, samples.size, samples.mapNotNull { it.durationMs?.takeIf { ms -> ms in 100..5_000 } }.takeIf { it.isNotEmpty() }?.average()?.toLong(),
            e.count { day(it.atMs) == date })
    }
    return CareReport(days, r.size, r.mapNotNull { it.durationMs?.takeIf { ms -> ms in 100..5_000 } }.takeIf { it.isNotEmpty() }?.average()?.toLong(), e.size)
}
    fun csv(report: CareReport, demo: Boolean): String = buildString {
        append("\uFEFFmode,date,attempts,average_ms,headlight_warnings\r\n")
        report.days.forEach { d ->
            append(if (demo) "demo" else "vehicle").append(',').append(d.day).append(',')
                .append(d.attempts).append(',').append(d.averageMs ?: "").append(',')
                .append(d.headlightWarnings).append("\r\n")
        }
    }
    fun shareText(records: List<ReactionRecord>, events: List<CareEvent>, demo: Boolean): String {
        val r = summarize(records, events, demo)
        return "꼼짝마차 · 최근 30일 ${if (demo) "시뮬레이션" else "차량"} 리포트\n" +
            "반응 체크 ${r.attempts}회 · 측정 평균 ${r.averageMs?.let { "$it ms" } ?: "기록 없음"}\n" +
            "전조등 안내 ${r.headlightWarnings}회\n" +
            "페달 신호 전달 지연이 포함된 앱 측정값입니다. 건강이나 운전 적합성을 판단하지 않습니다."

    }

}
