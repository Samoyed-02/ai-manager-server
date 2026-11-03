package com.samoyed.ai_manager_server.memory

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

/** FACT: 사실, PREFERENCE: 선호, ROUTINE: 루틴, GOAL: 목표, PROGRESS: 진행 상황 */
enum class MemoryCategory(val label: String) {
    FACT("사실"), PREFERENCE("선호"), ROUTINE("루틴"), GOAL("목표"), PROGRESS("진행 상황"), OTHER("기타"),
}

const val MAX_MEMORY_CONTENT = 500

@Entity
@Table(name = "memories")
class Memory(
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    var category: MemoryCategory,

    @Column(nullable = false, length = MAX_MEMORY_CONTENT)
    var content: String,

    @Column(name = "source_conversation_id")
    val sourceConversationId: UUID? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}
