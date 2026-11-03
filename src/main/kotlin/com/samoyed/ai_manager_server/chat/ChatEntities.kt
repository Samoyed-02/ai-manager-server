package com.samoyed.ai_manager_server.chat

import jakarta.persistence.*
import java.time.OffsetDateTime
import java.util.UUID

/** 대화가 어디서 왔는지. VOICE는 통화가 끝난 뒤 클라이언트가 보낸 기록이다 */
enum class ConversationChannel { CHAT, VOICE }

@Entity
@Table(name = "conversations")
class Conversation(
    @Column(length = 200)
    var title: String? = null,

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10, updatable = false)
    val channel: ConversationChannel = ConversationChannel.CHAT,
) {
    @Id
    val id: UUID = UUID.randomUUID()

    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now()

    @Column(name = "updated_at", nullable = false)
    var updatedAt: OffsetDateTime = OffsetDateTime.now()

    /**
     * 기억 추출 워커가 JDBC로 쓰는 값이라 JPA에서는 읽기만 한다.
     * 여기서 쓰게 두면 미리 읽어 둔 엔티티를 저장할 때 워커가 쓴 요약을 덮어쓴다
     */
    @Column(columnDefinition = "text", insertable = false, updatable = false)
    val summary: String? = null
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