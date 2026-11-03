package com.samoyed.ai_manager_server.routine

import jakarta.persistence.*
import java.time.DayOfWeek
import java.time.LocalTime
import java.time.OffsetDateTime
import java.util.UUID

/**
 * REMINDER: 정해 둔 문구를 그대로 알림으로, BRIEFING: 문구를 지시로 삼아 Claude가 브리핑을 작성,
 * REVIEW: 지난 7일 통계를 서버가 계산하고 Claude가 회고를 작성
 */
enum class RoutineKind(val label: String) { REMINDER("알림"), BRIEFING("브리핑"), REVIEW("회고") }

const val MAX_ROUTINE_TITLE = 100
const val MAX_ROUTINE_MESSAGE = 2_000

@Entity
@Table(name = "routines")
class Routine(
    @Column(nullable = false, length = MAX_ROUTINE_TITLE)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var kind: RoutineKind,

    @Column(nullable = false, length = MAX_ROUTINE_MESSAGE)
    var message: String,

    /** 쉼표로 구분한 요일(MON,WED). 비어 있으면 매일 */
    @Column(nullable = false, length = 30)
    var days: String,

    @Column(name = "time_of_day", nullable = false)
    var timeOfDay: LocalTime,

    @Column(name = "next_run_at", nullable = false)
    var nextRunAt: OffsetDateTime,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(nullable = false)
    var enabled: Boolean = true

    @Column(name = "last_run_at", insertable = false, updatable = false)
    val lastRunAt: OffsetDateTime? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()

    var dayOfWeeks: Set<DayOfWeek>
        get() = RoutineSchedule.parseDays(days)
        set(value) {
            days = RoutineSchedule.formatDays(value)
        }
}

@Entity
@Table(name = "notifications")
class Notification(
    @Column(name = "routine_id")
    val routineId: UUID?,

    @Column(nullable = false, length = 200)
    val title: String,

    @Column(nullable = false, columnDefinition = "text")
    val body: String,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "read_at")
    var readAt: OffsetDateTime? = null
}
