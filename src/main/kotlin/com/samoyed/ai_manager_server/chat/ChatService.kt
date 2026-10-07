package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.OffsetDateTime
import java.util.UUID

private const val SYSTEM_PROMPT = """
너는 사용자의 개인 AI 매니저야. 사용자는 백엔드 개발을 공부하는 대학생이야.
한국어로 답하고, 핵심부터 간결하게 말해. 코드는 마크다운 코드 블록으로 작성해.
"""

@Service
class ChatService(
    private val client: AnthropicClient,
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    @Value("\${claude.model}") private val model: String,
) {
    fun prepare(conversationId: UUID?, userText: String): Pair<UUID, List<ChatMessage>> {
        val conversation = conversationId?.let { conversations.findById(it).orElse(null) }
            ?: conversations.save(Conversation(title = userText.take(50)))

        messages.save(ChatMessage(conversation.id, "user", userText))

        val history = messages.findTop40ByConversationIdOrderByIdDesc(conversation.id)
            .reversed()
            .dropWhile { it.role != "user" }

        return conversation.id to history
    }

    fun stream(history: List<ChatMessage>, onDelta: (String) -> Unit): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .system(SYSTEM_PROMPT.trimIndent())
            .apply {
                history.forEach {
                    if (it.role == "user") addUserMessage(it.content) else addAssistantMessage(it.content)
                }
            }
            .build()

        val full = StringBuilder()
        client.messages().createStreaming(params).use { response ->
            response.stream().forEach { event ->
                event.contentBlockDelta()
                    .flatMap { it.delta().text() }
                    .ifPresent { delta ->
                        full.append(delta.text())
                        onDelta(delta.text())
                    }
            }
        }
        return full.toString()
    }

    fun saveAssistant(conversationId: UUID, text: String) {
        messages.save(ChatMessage(conversationId, "assistant", text))
        conversations.findById(conversationId).ifPresent {
            it.updatedAt = OffsetDateTime.now()
            conversations.save(it)
        }
    }
}