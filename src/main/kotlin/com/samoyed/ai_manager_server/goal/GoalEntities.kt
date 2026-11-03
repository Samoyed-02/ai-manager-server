package com.samoyed.ai_manager_server.goal

import com.samoyed.ai_manager_server.activity.ActivityCategory
import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class GoalStatus(val label: String) { ACTIVE("진행 중"), DONE("달성"), DROPPED("중단") }

const val MAX_GOAL_TITLE = 100
const val MAX_GOAL_DESCRIPTION = 1_000
/** 동시에 진행하는 목표 수. 많으면 맥락이 길어지고 집중도 흐려진다 */
const val MAX_ACTIVE_GOALS = 10

@Entity
@Table(name = "goals")
class Goal(
    @Column(nullable = false, length = MAX_GOAL_TITLE)
    var title: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var category: ActivityCategory,

    @Column(length = MAX_GOAL_DESCRIPTION)
    var description: String? = null,

    /** 한 주(월~일, 한국 시간)에 쓸 목표 시간(분) */
    @Column(name = "weekly_target_minutes")
    var weeklyTargetMinutes: Int? = null,

    @Column(name = "due_date")
    var dueDate: LocalDate? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var status: GoalStatus = GoalStatus.ACTIVE

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}

interface GoalRepository : JpaRepository<Goal, UUID> {
    fun findByStatusOrderByCreatedAtAsc(status: GoalStatus): List<Goal>
    fun countByStatus(status: GoalStatus): Long
    fun findAllByOrderByCreatedAtDesc(): List<Goal>
}
