package com.samoyed.ai_manager_server.chat

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

const val MAX_CHAT_MESSAGE = 20_000

data class ChatRequest(
    val conversationId: UUID? = null,

    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = MAX_CHAT_MESSAGE, message = "최대 {max}자까지 입력할 수 있습니다")
    val message: String,
)

@RestController
class ChatController(private val chat: ChatService) {
    private val log = LoggerFactory.getLogger(javaClass)

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@Valid @RequestBody req: ChatRequest): SseEmitter {
        val emitter = SseEmitter(180_000L)
        val prepared = chat.prepare(req.conversationId, req.message)

        Thread.startVirtualThread {
            try {
                chat.respond(prepared) { event -> emitter.send(event.toSse()) }
            } catch (e: Exception) {
                log.error("conversation {} respond failed", prepared.conversationId, e)
            } finally {
                runCatching { emitter.complete() }
            }
        }
        return emitter
    }

    private fun ChatEvent.toSse(): SseEmitter.SseEventBuilder = when (this) {
        is ChatEvent.Meta -> event("meta", mapOf("conversationId" to conversationId))
        is ChatEvent.Delta -> event("delta", mapOf("text" to text))
        is ChatEvent.Done -> event("done", mapOf("status" to status))
        is ChatEvent.TaskCreated -> event("task", mapOf("taskId" to taskId, "instruction" to instruction))
        is ChatEvent.Action -> event("action", mapOf("tool" to tool, "summary" to summary))
        is ChatEvent.Error -> event("error", mapOf("code" to code, "message" to message))
    }

    private fun event(name: String, data: Any): SseEmitter.SseEventBuilder =
        SseEmitter.event().name(name).data(data, MediaType.APPLICATION_JSON)
}
