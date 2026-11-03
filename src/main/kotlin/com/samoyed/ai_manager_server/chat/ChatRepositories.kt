package com.samoyed.ai_manager_server.chat

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID> {
    fun findTop50ByOrderByUpdatedAtDesc(): List<Conversation>

    /** 답변 저장과 대화 삭제가 겹치지 않게 행을 잠근다. 이미 삭제됐으면 null */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from Conversation c where c.id = :id")
    fun findForUpdate(id: UUID): Conversation?

    /** 메시지는 DB의 on delete cascade로 함께 지워진다. 지운 행 수를 돌려준다 */
    @Transactional
    @Modifying
    @Query("delete from Conversation c where c.id = :id")
    fun deleteReturningCount(id: UUID): Int
}

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findTop40ByConversationIdOrderByIdDesc(conversationId: UUID): List<ChatMessage>
    fun findByConversationIdOrderByIdAsc(conversationId: UUID): List<ChatMessage>
}