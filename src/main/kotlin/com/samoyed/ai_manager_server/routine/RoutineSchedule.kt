package com.samoyed.ai_manager_server.routine

import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** 루틴 시각은 한국 시간 기준이다 */
object RoutineSchedule {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")

    private val labels = mapOf(
        DayOfWeek.MONDAY to "월", DayOfWeek.TUESDAY to "화", DayOfWeek.WEDNESDAY to "수", DayOfWeek.THURSDAY to "목",
        DayOfWeek.FRIDAY to "금", DayOfWeek.SATURDAY to "토", DayOfWeek.SUNDAY to "일",
    )

    /** "MON" 같은 세 글자 약어 */
    fun parseDay(code: String): DayOfWeek? = DayOfWeek.entries.firstOrNull { it.name.take(3) == code.trim().uppercase() }

    fun parseDays(days: String): Set<DayOfWeek> =
        days.split(",").filter { it.isNotBlank() }.mapNotNull { parseDay(it) }.toSortedSet()

    fun formatDays(days: Set<DayOfWeek>): String = days.sorted().joinToString(",") { it.name.take(3) }

    /** after 이후(같은 시각은 포함하지 않음) 처음 오는 실행 시각. days가 비면 매일 */
    fun nextRun(days: Set<DayOfWeek>, time: LocalTime, after: OffsetDateTime): OffsetDateTime {
        val base = after.atZoneSameInstant(ZONE)
        var candidate: ZonedDateTime = base.toLocalDate().atTime(time).atZone(ZONE)
        repeat(8) {
            if (candidate.isAfter(base) && (days.isEmpty() || candidate.dayOfWeek in days)) return candidate.toOffsetDateTime()
            candidate = candidate.plusDays(1).toLocalDate().atTime(time).atZone(ZONE)
        }
        error("unreachable: no run time within a week")
    }

    /** "매일 08:00", "월·수·금 22:00" */
    fun label(days: Set<DayOfWeek>, time: LocalTime): String {
        val dayText = if (days.isEmpty() || days.size == 7) "매일" else days.sorted().joinToString("·") { labels.getValue(it) }
        return "$dayText ${"%02d:%02d".format(time.hour, time.minute)}"
    }
}
