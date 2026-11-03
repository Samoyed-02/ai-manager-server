package com.samoyed.ai_manager_server.goal

import com.samoyed.ai_manager_server.activity.ActivityCategory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.LocalDate

class GoalProgressTest {

    @Test
    fun `한 주는 월요일에 시작해 일요일에 끝난다`() {
        // 2026-11-03은 화요일, 11-08은 일요일, 11-09는 월요일
        assertEquals(LocalDate.of(2026, 11, 2)..LocalDate.of(2026, 11, 8), weekOf(LocalDate.of(2026, 11, 3)))
        assertEquals(LocalDate.of(2026, 11, 2)..LocalDate.of(2026, 11, 8), weekOf(LocalDate.of(2026, 11, 8)))
        assertEquals(LocalDate.of(2026, 11, 9)..LocalDate.of(2026, 11, 15), weekOf(LocalDate.of(2026, 11, 9)))
    }

    @Test
    fun `시간과 진행 문구`() {
        assertEquals("0분", formatMinutes(0))
        assertEquals("45분", formatMinutes(45))
        assertEquals("2시간", formatMinutes(120))
        assertEquals("1시간 30분", formatMinutes(90))

        val goal = Goal("정보처리기사 합격", ActivityCategory.STUDY, weeklyTargetMinutes = 600)
        assertEquals("정보처리기사 합격 (D-28): 이번 주 6시간 / 목표 10시간", GoalProgress(goal, 360, 28).line())
        assertEquals("정보처리기사 합격 (마감 2일 지남): 이번 주 0분 / 목표 10시간", GoalProgress(goal, 0, -2).line())
        assertEquals("운동 습관: 이번 주 1시간", GoalProgress(Goal("운동 습관", ActivityCategory.EXERCISE), 60, null).line())
    }
}
