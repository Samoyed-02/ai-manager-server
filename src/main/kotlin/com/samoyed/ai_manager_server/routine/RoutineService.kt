package com.samoyed.ai_manager_server.routine

import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/** 루틴을 너무 많이 만들어 비용이 새지 않게 한다 */
const val MAX_ROUTINES = 30

/** 입력이 잘못됐다. 도구는 모델에게, API는 400으로 사유를 돌려준다 */
class RoutineValidationException(message: String) : RuntimeException(message)

data class RoutineChange(
    val title: String? = null,
    val kind: RoutineKind? = null,
    val message: String? = null,
    val time: LocalTime? = null,
    val days: Set<DayOfWeek>? = null,
    val enabled: Boolean? = null,
)

/** 루틴 생성·수정·삭제. 말(도구)과 화면(API)이 같은 규칙을 쓴다 */
@Service
class RoutineService(private val routines: RoutineRepository) {

    @Transactional
    fun create(title: String, kind: RoutineKind, message: String, time: LocalTime, days: Set<DayOfWeek>): Routine {
        if (routines.count() >= MAX_ROUTINES) throw RoutineValidationException("루틴은 최대 ${MAX_ROUTINES}개까지야. 안 쓰는 루틴을 먼저 지워.")
        validate(title, message)
        val routine = Routine(
            title = title, kind = kind, message = message,
            days = RoutineSchedule.formatDays(days), timeOfDay = time,
            nextRunAt = RoutineSchedule.nextRun(days, time, OffsetDateTime.now()),
        )
        return routines.save(routine)
    }

    @Transactional
    fun update(id: UUID, change: RoutineChange): Routine {
        val routine = routines.findById(id).orElseThrow { RoutineValidationException("id $id 루틴이 없어. 목록에서 id를 다시 확인해.") }
        change.title?.let { routine.title = it }
        change.kind?.let { routine.kind = it }
        change.message?.let { routine.message = it }
        change.time?.let { routine.timeOfDay = it }
        change.days?.let { routine.dayOfWeeks = it }
        change.enabled?.let { routine.enabled = it }
        validate(routine.title, routine.message)
        // 시각·요일이 바뀌었거나 다시 켰으면 지금부터 다음 실행 시각을 다시 잡는다
        routine.nextRunAt = RoutineSchedule.nextRun(routine.dayOfWeeks, routine.timeOfDay, OffsetDateTime.now())
        routine.updatedAt = OffsetDateTime.now()
        return routines.save(routine)
    }

    @Transactional
    fun delete(id: UUID): Routine {
        val routine = routines.findById(id).orElseThrow { RoutineValidationException("id $id 루틴이 없어. 목록에서 id를 다시 확인해.") }
        routines.delete(routine)
        return routine
    }

    private fun validate(title: String, message: String) {
        if (title.isBlank() || title.length > MAX_ROUTINE_TITLE) throw RoutineValidationException("title은 1~${MAX_ROUTINE_TITLE}자여야 해.")
        if (message.isBlank() || message.length > MAX_ROUTINE_MESSAGE) throw RoutineValidationException("message는 1~${MAX_ROUTINE_MESSAGE}자여야 해.")
    }
}

fun Routine.scheduleLabel(): String = RoutineSchedule.label(dayOfWeeks, timeOfDay)
