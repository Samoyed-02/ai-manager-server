package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import com.samoyed.ai_manager_server.claude.classifyClaudeError
import com.samoyed.ai_manager_server.profile.ContextService

private const val SYSTEM_PROMPT = """
너는 사용자의 개인 AI 매니저야. 아래 사용자 프로필을 참고해서 맞춤형으로 답해.
한국어로 답하고, 핵심부터 간결하게 말해. 코드는 마크다운 코드 블록으로 작성해.
"""

/** prepare()가 저장한 상태. 응답이 실패하면 이 정보로 사용자 메시지를 되돌린다 */
data class PreparedChat(
    val conversationId: UUID,
    val isNewConversation: Boolean,
    val userMessageId: Long,
    val history: List<ChatMessage>,
)

/** 클라이언트로 보내는 이벤트. 컨트롤러가 SSE 이벤트로 바꾼다 */
sealed interface ChatEvent {
    data class Meta(val conversationId: UUID) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Done(val status: MessageStatus) : ChatEvent
    /** code: ClaudeErrorKind 이름 또는 REFUSED / EMPTY_RESPONSE. message는 사용자에게 보여줄 문구 */
    data class Error(val code: String, val message: String) : ChatEvent {
        companion object {
            fun of(e: Throwable) = classifyClaudeError(e).let { Error(it.name, it.userMessage) }
        }
    }
}

@Service
class ChatService(
    private val client: AnthropicClient,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    private val context: ContextService,
    private val tx: TransactionTemplate,
    @Value("\${claude.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun prepare(conversationId: UUID?, userText: String): PreparedChat {
        val existing = conversationId?.let { conversations.findById(it).orElse(null) }
        val conversation = existing ?: conversations.save(Conversation(title = userText.take(50)))

        val userMessage = messages.save(ChatMessage(conversation.id, "user", userText))

        val history = messages.findTop40ByConversationIdOrderByIdDesc(conversation.id)
            .reversed()
            .dropWhile { it.role != "user" }

        return PreparedChat(conversation.id, existing == null, userMessage.id!!, history)
    }

    /**
     * Claude 응답을 스트리밍하며 sink로 보내고, 끝나면 결과를 저장한다.
     * sink가 예외를 던지면(연결 끊김, SSE 타임아웃) 전송만 멈추고 응답은 끝까지 받아 저장한다.
     * 텍스트가 하나도 없으면 사용자 메시지를 되돌려, 답 없는 질문이 기록에 남지 않게 한다.
     */
    fun respond(prepared: PreparedChat, sink: (ChatEvent) -> Unit) {
        var connected = true
        fun emit(event: ChatEvent) {
            if (!connected) return
            try {
                sink(event)
            } catch (e: Exception) {
                connected = false
                log.info("conversation {} client disconnected, continuing to save reply: {}", prepared.conversationId, e.toString())
            }
        }

        emit(ChatEvent.Meta(prepared.conversationId))

        val text = StringBuilder()
        var stopReason: StopReason? = null
        var failure: Exception? = null
        try {
            client.messages().createStreaming(buildParams(prepared.history)).use { response ->
                response.stream().forEach { event ->
                    event.contentBlockDelta()
                        .flatMap { it.delta().text() }
                        .ifPresent { delta ->
                            text.append(delta.text())
                            emit(ChatEvent.Delta(delta.text()))
                        }
                    event.messageDelta()
                        .flatMap { it.delta().stopReason() }
                        .ifPresent { stopReason = it }
                }
            }
        } catch (e: Exception) {
            log.error("conversation {} streaming failed", prepared.conversationId, e)
            failure = e
        }

        val status = when {
            failure != null -> MessageStatus.INTERRUPTED
            stopReason == StopReason.MAX_TOKENS -> MessageStatus.TRUNCATED
            stopReason == StopReason.REFUSAL -> MessageStatus.REFUSED
            stopReason == StopReason.END_TURN || stopReason == StopReason.STOP_SEQUENCE -> MessageStatus.COMPLETE
            else -> MessageStatus.INTERRUPTED
        }

        if (text.isBlank()) {
            discardUserMessage(prepared)
            emit(
                when {
                    failure != null -> ChatEvent.Error.of(failure)
                    status == MessageStatus.REFUSED -> ChatEvent.Error("REFUSED", "요청이 거절됐어요")
                    else -> {
                        log.warn("conversation {} empty reply (stop_reason={})", prepared.conversationId, stopReason)
                        ChatEvent.Error("EMPTY_RESPONSE", "응답이 비어 있어요. 다시 시도해 주세요")
                    }
                }
            )
            return
        }

        saveAssistant(prepared.conversationId, text.toString(), status)
        if (failure != null) {
            emit(ChatEvent.Error.of(failure))
        } else {
            emit(ChatEvent.Done(status))
        }
    }

    private fun buildParams(history: List<ChatMessage>): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .system(SYSTEM_PROMPT.trimIndent() + "\n\n" + context.forClaude())
            .apply {
                history.forEach {
                    if (it.role == "user") addUserMessage(it.content) else addAssistantMessage(it.content)
                }
            }
            .build()

    private fun saveAssistant(conversationId: UUID, text: String, status: MessageStatus) {
        tx.executeWithoutResult {
            messages.save(ChatMessage(conversationId, "assistant", text, status))
            conversations.findById(conversationId).ifPresent {
                it.updatedAt = OffsetDateTime.now()
                conversations.save(it)
            }
        }
    }

    /** 답을 하나도 받지 못한 질문을 지운다. 이번 요청으로 새로 만든 대화면 대화째 지운다 */
    private fun discardUserMessage(prepared: PreparedChat) {
        tx.executeWithoutResult {
            messages.deleteById(prepared.userMessageId)
            if (prepared.isNewConversation) conversations.deleteById(prepared.conversationId)
        }
    }
}
