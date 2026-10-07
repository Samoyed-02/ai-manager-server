package com.samoyed.ai_manager_server.chat

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter
import java.util.UUID

data class ChatRequest(
    val conversationId: UUID? = null,
    @field:NotBlank val message: String,
)

@RestController
class ChatController(private val chat: ChatService) {

    @PostMapping("/chat", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun chat(@Valid @RequestBody req: ChatRequest): SseEmitter {
        val emitter = SseEmitter(180_000L)
        val (conversationId, history) = chat.prepare(req.conversationId, req.message)

        Thread.startVirtualThread {
            try {
                emitter.send(
                    SseEmitter.event().name("meta")
                        .data(mapOf("conversationId" to conversationId), MediaType.APPLICATION_JSON)
                )
                val full = chat.stream(history) { delta ->
                    emitter.send(
                        SseEmitter.event().name("delta")
                            .data(mapOf("text" to delta), MediaType.APPLICATION_JSON)
                    )
                }
                chat.saveAssistant(conversationId, full)
                emitter.send(SseEmitter.event().name("done").data("{}"))
                emitter.complete()
            } catch (e: Exception) {
                runCatching {
                    emitter.send(
                        SseEmitter.event().name("error")
                            .data(mapOf("message" to (e.message ?: "unknown")), MediaType.APPLICATION_JSON)
                    )
                }
                emitter.complete()
            }
        }
        return emitter
    }
}