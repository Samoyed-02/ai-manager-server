package com.samoyed.ai_manager_server.goal

import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters
import java.util.UUID

/** 입력이 잘못됐다. 도구는 모델에게, API는 400으로 사유를 돌려준다 */
class GoalValidationException(message: String) : RuntimeException(message)

data class GoalChange(
    val title: String? = null,
    val description: String? = null,
    val category: ActivityCategory? = null,
    val weeklyTargetMinutes: Int? = null,
    val dueDate: LocalDate? = null,
    val status: GoalStatus? = null,
)

/** 이번 주(월~일, 한국 시간) 진행 상황. 숫자는 서버가 계산해 AI가 합계를 틀리게 말하지 않게 한다 */
data class GoalProgress(val goal: Goal, val weekMinutes: Long, val daysLeft: Long?) {
    /** "정보처리기사 합격 (D-28): 이번 주 6시간 / 목표 10시간" */
    fun line(): String {
        val dday = daysLeft?.let { if (it >= 0) " (D-$it)" else " (마감 ${-it}일 지남)" } ?: ""
        val target = goal.weeklyTargetMinutes?.let { " / 목표 ${formatMinutes(it.toLong())}" } ?: ""
        return "${goal.title}$dday: 이번 주 ${formatMinutes(weekMinutes)}$target"
    }
}

/** 90 → "1시간 30분", 120 → "2시간", 0 → "0분" */
fun formatMinutes(minutes: Long): String {
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0L -> "${m}분"
        m == 0L -> "${h}시간"
        else -> "${h}시간 ${m}분"
    }
}

/** 한 주는 월요일에 시작한다 (한국 시간) */
fun weekOf(date: LocalDate): ClosedRange<LocalDate> {
    val monday = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    return monday..monday.plusDays(6)
}

@Service
class GoalService(private val goals: GoalRepository, private val activities: ActivityLogRepository) {

    @Transactional
    fun create(title: String, category: ActivityCategory, description: String?, weeklyTargetMinutes: Int?, dueDate: LocalDate?): Goal {
        if (goals.countByStatus(GoalStatus.ACTIVE) >= MAX_ACTIVE_GOALS) {
            throw GoalValidationException("진행 중인 목표는 최대 ${MAX_ACTIVE_GOALS}개야. 달성하거나 중단한 목표를 먼저 정리해.")
        }
        val goal = Goal(title, category, description, weeklyTargetMinutes, dueDate)
        validate(goal)
        return goals.save(goal)
    }

    @Transactional
    fun update(id: UUID, change: GoalChange): Goal {
        val goal = goals.findById(id).orElseThrow { GoalValidationException("id $id 목표가 없어. 목록에서 id를 다시 확인해.") }
        if (change.status == GoalStatus.ACTIVE && goal.status != GoalStatus.ACTIVE && goals.countByStatus(GoalStatus.ACTIVE) >= MAX_ACTIVE_GOALS) {
            throw GoalValidationException("진행 중인 목표는 최대 ${MAX_ACTIVE_GOALS}개야.")
        }
        change.title?.let { goal.title = it }
        change.description?.let { goal.description = it }
        change.category?.let { goal.category = it }
        change.weeklyTargetMinutes?.let { goal.weeklyTargetMinutes = it }
        change.dueDate?.let { goal.dueDate = it }
        change.status?.let { goal.status = it }
        validate(goal)
        goal.updatedAt = OffsetDateTime.now()
        return goals.save(goal)
    }

    fun progress(goal: Goal, today: LocalDate = LocalDate.now(RoutineSchedule.ZONE)): GoalProgress {
        val week = weekOf(today)
        return GoalProgress(
            goal,
            activities.sumMinutesForGoal(goal.id, week.start, week.endInclusive),
            goal.dueDate?.let { ChronoUnit.DAYS.between(today, it) },
        )
    }

    fun activeProgress(): List<GoalProgress> = goals.findByStatusOrderByCreatedAtAsc(GoalStatus.ACTIVE).map { progress(it) }

    private fun validate(goal: Goal) {
        if (goal.title.isBlank() || goal.title.length > MAX_GOAL_TITLE) throw GoalValidationException("title은 1~${MAX_GOAL_TITLE}자여야 해.")
        if ((goal.description?.length ?: 0) > MAX_GOAL_DESCRIPTION) throw GoalValidationException("description은 최대 ${MAX_GOAL_DESCRIPTION}자야.")
        goal.weeklyTargetMinutes?.let { if (it !in 1..10_080) throw GoalValidationException("weekly_target_minutes는 1~10080(분)이어야 해.") }
    }
}
