package com.samoyed.ai_manager_server.chat

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ConversationRepository : JpaRepository<Conversation, UUID>

interface ChatMessageRepository : JpaRepository<ChatMessage, Long> {
    fun findTop40ByConversationIdOrderByIdDesc(conversationId: UUID): List<ChatMessage>
}