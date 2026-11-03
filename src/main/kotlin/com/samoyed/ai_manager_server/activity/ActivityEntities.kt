package com.samoyed.ai_manager_server.activity

import jakarta.persistence.*
import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

enum class ActivityCategory(val label: String) { STUDY("공부"), EXERCISE("운동"), WORK("일"), OTHER("기타") }

const val MAX_ACTIVITY_DESCRIPTION = 300

@Entity
@Table(name = "activity_logs")
class ActivityLog(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val category: ActivityCategory,

    @Column(nullable = false, length = MAX_ACTIVITY_DESCRIPTION)
    val description: String,

    /** 걸린 시간(분). 말하지 않았으면 null */
    val minutes: Int?,

    @Column(name = "occurred_on", nullable = false)
    val occurredOn: LocalDate,

    @Column(name = "source_conversation_id")
    val sourceConversationId: UUID? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
}

interface ActivityLogRepository : JpaRepository<ActivityLog, UUID> {
    fun findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(from: LocalDate, to: LocalDate): List<ActivityLog>
}
