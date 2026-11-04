package com.samoyed.ai_manager_server.chat

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class ConversationDto(val id: UUID, val title: String?, val channel: ConversationChannel, val updatedAt: OffsetDateTime)
data class MessageDto(
    val id: Long,
    val role: String,
    val content: String,
    val status: MessageStatus,
    val createdAt: OffsetDateTime,
    /** 이 답변에서 서버가 실행한 작업 (활동 기록, 목표 등록, 웹 검색 등). 없으면 빈 목록 */
    val actions: List<String>,
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
            .map { ConversationDto(it.id, it.title, it.channel, it.updatedAt) }

    @GetMapping("/{id}/messages")
    fun messages(@PathVariable id: UUID): List<MessageDto> =
        messages.findByConversationIdOrderByIdAsc(id)
            .map { MessageDto(it.id!!, it.role, it.content, it.status, it.createdAt, it.actions?.lines().orEmpty()) }

    /** 답변이 스트리밍되는 중이면 그 답변은 저장되지 않는다 (ChatService.saveAssistant) */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: UUID) {
        if (conversations.deleteReturningCount(id) == 0) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }
}