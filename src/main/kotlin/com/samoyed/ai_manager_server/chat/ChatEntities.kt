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

@Entity
@Table(name = "messages")
class ChatMessage(
    @Column(name = "conversation_id", nullable = false)
    val conversationId: UUID,

    @Column(nullable = false, length = 20)
    val role: String,

    @Column(nullable = false, columnDefinition = "text")
    val content: String,
) {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()
}