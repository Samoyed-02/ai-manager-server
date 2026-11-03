package com.samoyed.ai_manager_server.routine

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.DayOfWeek.FRIDAY
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.WEDNESDAY
import java.time.LocalTime
import java.time.OffsetDateTime

class RoutineScheduleTest {
    /** 2026-11-03은 화요일. 한국 시간으로 쓴다 */
    private fun kst(text: String) = OffsetDateTime.parse("${text}+09:00")

    @Test
    fun `매일 루틴은 오늘 시각이 남았으면 오늘, 지났으면 내일`() {
        val eight = LocalTime.of(8, 0)
        assertEquals(kst("2026-11-03T08:00:00"), RoutineSchedule.nextRun(emptySet(), eight, kst("2026-11-03T07:59:00")))
        assertEquals(kst("2026-11-04T08:00:00"), RoutineSchedule.nextRun(emptySet(), eight, kst("2026-11-03T08:00:00")))
    }

    @Test
    fun `요일이 정해지면 다음 해당 요일로 간다`() {
        val days = setOf(MONDAY, WEDNESDAY, FRIDAY)
        val ten = LocalTime.of(22, 0)
        assertEquals(kst("2026-11-04T22:00:00"), RoutineSchedule.nextRun(days, ten, kst("2026-11-03T23:00:00")))
        // 금요일 밤이 지나면 다음 주 월요일
        assertEquals(kst("2026-11-09T22:00:00"), RoutineSchedule.nextRun(days, ten, kst("2026-11-06T22:30:00")))
    }

    @Test
    fun `서버 시간이 UTC여도 한국 날짜와 요일로 계산한다`() {
        // UTC 11/3 23:30 = 한국 11/4(수) 08:30 → 다음 수요일 09:00은 같은 날
        val after = OffsetDateTime.parse("2026-11-03T23:30:00Z")
        assertEquals(kst("2026-11-04T09:00:00"), RoutineSchedule.nextRun(setOf(WEDNESDAY), LocalTime.of(9, 0), after).atZoneSameInstant(RoutineSchedule.ZONE).toOffsetDateTime())
    }

    @Test
    fun `요일 문자열과 라벨`() {
        assertEquals(setOf(MONDAY, FRIDAY), RoutineSchedule.parseDays("fri, MON"))
        assertEquals("MON,FRI", RoutineSchedule.formatDays(setOf(FRIDAY, MONDAY)))
        assertEquals("매일 08:00", RoutineSchedule.label(emptySet(), LocalTime.of(8, 0)))
        assertEquals("월·수·금 22:05", RoutineSchedule.label(setOf(FRIDAY, MONDAY, WEDNESDAY), LocalTime.of(22, 5)))
    }
}
