package com.samoyed.ai_manager_server.chat

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.OffsetDateTime
import java.util.UUID

data class ConversationDto(val id: UUID, val title: String?, val updatedAt: OffsetDateTime)
data class MessageDto(
    val id: Long,
    val role: String,
    val content: String,
    val status: MessageStatus,
    val createdAt: OffsetDateTime,
)

@RestController
@RequestMapping("/conversations")
class ConversationController(
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
) {
    @GetMapping
    fun list(): List<ConversationDto> =
        conversations.findTop50ByOrderByUpdatedAtDesc()
            .map { ConversationDto(it.id, it.title, it.updatedAt) }

    @GetMapping("/{id}/messages")
    fun messages(@PathVariable id: UUID): List<MessageDto> =
        messages.findByConversationIdOrderByIdAsc(id)
            .map { MessageDto(it.id!!, it.role, it.content, it.status, it.createdAt) }
}