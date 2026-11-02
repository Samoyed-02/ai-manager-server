package com.samoyed.ai_manager_server.chat

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

@Entity
@Table(name = "conversations")
class Conversation(
    @Column(length = 200)
    var title: String? = null,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()
}

/**
 * COMPLETE: 정상 종료, TRUNCATED: 출력 한도에서 잘림, REFUSED: Claude가 거절,
 * INTERRUPTED: 스트리밍 도중 오류로 부분 응답만 있음
 */
enum class MessageStatus { COMPLETE, TRUNCATED, REFUSED, INTERRUPTED }

@Entity
@Table(name = "messages")
class ChatMessage(
    @Column(name = "conversation_id", nullable = false)
    val conversationId: UUID,

    @Column(nullable = false, length = 20)
    val role: String,

    @Column(nullable = false, columnDefinition = "text")
    val content: String,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    val status: MessageStatus = MessageStatus.COMPLETE,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
}