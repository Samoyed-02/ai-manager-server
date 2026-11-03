package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.core.type.TypeReference
import com.samoyed.ai_manager_server.tool.ToolContext
import com.samoyed.ai_manager_server.tool.ToolRegistry
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.ToolUnion
import com.anthropic.models.messages.UserLocation
import com.anthropic.models.messages.WebFetchTool20260209
import com.anthropic.models.messages.WebSearchTool20260209
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import java.time.OffsetDateTime
import java.util.UUID
import com.samoyed.ai_manager_server.claude.ClaudeCall
import com.samoyed.ai_manager_server.claude.classifyClaudeError
import com.samoyed.ai_manager_server.claude.elapsedMs
import com.samoyed.ai_manager_server.claude.logClaudeCall
import com.samoyed.ai_manager_server.profile.ContextService

private const val SYSTEM_PROMPT = """
너는 사용자의 개인 AI 매니저야. 아래 사용자 프로필과 지난 대화에서 기억하는 것을 참고해서 맞춤형으로 답해.
한국어로 답하고, 핵심부터 간결하게 말해. 코드는 마크다운 코드 블록으로 작성해.
최신 정보나 사실 확인이 필요할 때만 웹 검색을 쓰고, 일반 지식이나 대화 내용만으로 답할 수 있으면 검색하지 마.
출처 목록은 서버가 답변 끝에 붙이니 직접 쓰지 마.
말로 요청한 일은 도구로 처리해: 긴 문서 작성은 create_task, "기억해"는 save_memory, 반복 알림·브리핑은 루틴 도구, 한 일은 log_activity.
도구로 처리했으면 무엇을 했는지 짧게 알려 주고, 삭제처럼 되돌릴 수 없는 일은 먼저 확인받아.
"""

/** 질문 하나에 쓸 수 있는 웹 검색·페이지 읽기 횟수. 검색은 1,000회당 $10 */
private const val MAX_WEB_SEARCHES = 3L
private const val MAX_WEB_FETCHES = 3L
/** 페이지 하나를 읽을 때 컨텍스트에 넣을 최대 토큰. 큰 문서가 입력 토큰을 크게 늘리지 않게 한다 */
private const val MAX_FETCH_CONTENT_TOKENS = 20_000L
/** 서버 쪽 도구 반복이 한도에 걸려 pause_turn으로 끝나면 이어 받는 최대 횟수 */
private const val MAX_CONTINUATIONS = 3
/** 우리 서버가 실행하는 도구의 결과를 돌려주고 이어 받는 최대 횟수. 목록을 본 뒤 수정하는 식으로 연달아 쓸 수 있게 한다 */
private const val MAX_TOOL_ROUNDS = 4
private const val MAX_SOURCES = 10

private val WEB_TOOLS = listOf(
    ToolUnion.ofWebSearchTool20260209(
        WebSearchTool20260209.builder()
            .maxUses(MAX_WEB_SEARCHES)
            .userLocation(UserLocation.builder().country("KR").timezone("Asia/Seoul").build())
            .build()
    ),
    ToolUnion.ofWebFetchTool20260209(
        WebFetchTool20260209.builder()
            .maxUses(MAX_WEB_FETCHES)
            .maxContentTokens(MAX_FETCH_CONTENT_TOKENS)
            .build()
    ),
)

/** 웹 검색 인용이나 읽은 페이지. 사용자에게 보여줄 때 출처를 함께 표시해야 한다 */
private data class Source(val url: String, val title: String?)

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
    /** create_task 도구로 작업을 만들었다 */
    data class TaskCreated(val taskId: UUID, val instruction: String) : ChatEvent
    /** 그 밖의 도구로 무언가를 했다. summary는 사용자에게 보여줄 한 줄 */
    data class Action(val tool: String, val summary: String) : ChatEvent
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
    private val tools: ToolRegistry,
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
        val sources = LinkedHashMap<String, Source>()
        var stopReason: StopReason? = null
        var failure: Exception? = null
        // 토큰·도구 사용량은 요청마다 받아 합친다. pause_turn으로 이어 받으면 요청이 여러 번이 된다
        val usage = UsageTotals()
        var continuations = 0
        var toolRounds = 0
        var toolCalls = 0
        val usedInReply = mutableMapOf<String, Int>()
        val toolContext = ToolContext(ConversationChannel.CHAT, prepared.conversationId)
        var params = buildParams(prepared)
        val startedAt = System.nanoTime()
        try {
            while (true) {
                val result = streamOnce(params, usage, sources, prepared.conversationId) { delta ->
                    text.append(delta)
                    emit(ChatEvent.Delta(delta))
                }
                stopReason = result.stopReason
                // 이어 보낼 때는 받은 응답(thinking·도구 블록 포함)을 바꾸지 않고 그대로 붙인다
                val message = result.message ?: break
                when {
                    // 서버 쪽 도구 반복이 한도에 걸렸다. 그대로 붙여 보내면 이어서 진행한다
                    stopReason == StopReason.PAUSE_TURN && continuations < MAX_CONTINUATIONS -> {
                        params = params.toBuilder().addMessage(message).build()
                        continuations++
                    }
                    // 우리 서버가 실행할 도구를 불렀다. 모든 tool_use에 tool_result를 붙여 돌려준다
                    stopReason == StopReason.TOOL_USE && toolRounds < MAX_TOOL_ROUNDS -> {
                        val results = message.content().mapNotNull { it.toolUse().orElse(null) }.map { block ->
                            toolCalls++
                            val input = runCatching { block._input().convert(object : TypeReference<Map<String, Any?>>() {}) }.getOrNull()
                            val outcome = tools.execute(block.name(), input, toolContext, usedInReply)
                            when {
                                outcome.task != null -> emit(ChatEvent.TaskCreated(outcome.task.id, outcome.task.instruction))
                                outcome.ok && outcome.summary != null -> emit(ChatEvent.Action(block.name(), outcome.summary))
                            }
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(block.id()).content(outcome.content).isError(!outcome.ok)
                                    .build()
                            )
                        }
                        if (results.isEmpty()) break
                        params = params.toBuilder().addMessage(message).addUserMessageOfBlockParams(results).build()
                        toolRounds++
                    }
                    else -> break
                }
            }
        } catch (e: Exception) {
            log.error("conversation {} streaming failed", prepared.conversationId, e)
            failure = e
        }
        logClaudeCall(
            ClaudeCall(
                kind = "chat", id = prepared.conversationId, model = model, stopReason = stopReason,
                inputTokens = usage.inputTokens, outputTokens = usage.outputTokens,
                cacheReadTokens = usage.cacheReadTokens, cacheCreationTokens = usage.cacheCreationTokens,
                durationMs = elapsedMs(startedAt), historyMessages = prepared.history.size,
                webSearchRequests = usage.webSearchRequests, webFetchRequests = usage.webFetchRequests,
                continuations = continuations, toolCalls = toolCalls,
                error = failure?.let { classifyClaudeError(it) },
            )
        )

        if (text.isNotBlank() && sources.isNotEmpty()) {
            val footer = sourcesFooter(sources.values)
            text.append(footer)
            emit(ChatEvent.Delta(footer))
        }

        val status = when {
            failure != null -> MessageStatus.INTERRUPTED
            stopReason == StopReason.MAX_TOKENS -> MessageStatus.TRUNCATED
            // 이어 받기 한도를 넘겨 끝까지 받지 못했다
            stopReason == StopReason.PAUSE_TURN || stopReason == StopReason.TOOL_USE -> MessageStatus.TRUNCATED
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

        if (!saveAssistant(prepared.conversationId, text.toString(), status)) {
            log.info("conversation {} deleted while replying, reply not saved", prepared.conversationId)
            emit(ChatEvent.Error("CONVERSATION_DELETED", "대화가 삭제되어 답변을 저장하지 않았어요"))
            return
        }
        if (failure != null) {
            emit(ChatEvent.Error.of(failure))
        } else {
            emit(ChatEvent.Done(status))
        }
    }

    private class UsageTotals {
        var inputTokens: Long? = null
        var outputTokens: Long? = null
        var cacheReadTokens: Long? = null
        var cacheCreationTokens: Long? = null
        var webSearchRequests: Long? = null
        var webFetchRequests: Long? = null
    }

    private class StreamResult(val stopReason: StopReason?, val message: Message?)

    private fun Long?.plus(n: Long?): Long? = if (this == null && n == null) null else (this ?: 0) + (n ?: 0)

    /**
     * 요청 한 번을 스트리밍한다. 텍스트 조각은 onText로 넘기고, 출처와 사용량을 모은다.
     * pause_turn이면 이어 보내야 하므로 응답 전체를 MessageAccumulator로 모아 돌려준다.
     */
    private fun streamOnce(
        params: MessageCreateParams,
        usage: UsageTotals,
        sources: MutableMap<String, Source>,
        conversationId: UUID,
        onText: (String) -> Unit,
    ): StreamResult {
        val accumulator = MessageAccumulator.create()
        var accumulating = true
        var stopReason: StopReason? = null
        // 입력 토큰은 message_start에, 출력 토큰·도구 사용 횟수는 message_delta에 누적값으로 온다
        var input: Long? = null
        var cacheRead: Long? = null
        var cacheCreation: Long? = null
        var output: Long? = null
        var searches: Long? = null
        var fetches: Long? = null
        try {
            client.messages().createStreaming(params).use { response ->
                response.stream().forEach { event ->
                    // 모으기에 실패해도 답변은 계속 받는다. 이어 받기만 못 하게 된다
                    if (accumulating) {
                        try {
                            accumulator.accumulate(event)
                        } catch (e: Exception) {
                            accumulating = false
                            log.warn("conversation {} could not accumulate stream, pause_turn continuation disabled: {}", conversationId, e.toString())
                        }
                    }
                    event.messageStart().ifPresent {
                        val u = it.message().usage()
                        input = u.inputTokens()
                        cacheRead = u.cacheReadInputTokens().orElse(null)
                        cacheCreation = u.cacheCreationInputTokens().orElse(null)
                    }
                    event.contentBlockStart()
                        .flatMap { it.contentBlock().webFetchToolResult() }
                        .flatMap { it.content().webFetchBlock() }
                        .ifPresent { sources.putIfAbsent(it.url(), Source(it.url(), it.content().title().orElse(null))) }
                    event.contentBlockDelta().ifPresent { blockDelta ->
                        blockDelta.delta().text().ifPresent { onText(it.text()) }
                        blockDelta.delta().citations()
                            .flatMap { it.citation().webSearchResultLocation() }
                            .ifPresent { sources.putIfAbsent(it.url(), Source(it.url(), it.title().orElse(null))) }
                    }
                    event.messageDelta().ifPresent {
                        it.delta().stopReason().ifPresent { reason -> stopReason = reason }
                        output = it.usage().outputTokens()
                        it.usage().serverToolUse().ifPresent { tools ->
                            searches = tools.webSearchRequests()
                            fetches = tools.webFetchRequests()
                        }
                    }
                }
            }
        } finally {
            usage.inputTokens = usage.inputTokens.plus(input)
            usage.cacheReadTokens = usage.cacheReadTokens.plus(cacheRead)
            usage.cacheCreationTokens = usage.cacheCreationTokens.plus(cacheCreation)
            usage.outputTokens = usage.outputTokens.plus(output)
            usage.webSearchRequests = usage.webSearchRequests.plus(searches)
            usage.webFetchRequests = usage.webFetchRequests.plus(fetches)
        }
        // 이어 보내야 하는 경우만 응답 전체가 필요하다. max_tokens·refusal로 끝난 응답의 도구는 실행하지 않는다
        val message = if ((stopReason == StopReason.PAUSE_TURN || stopReason == StopReason.TOOL_USE) && accumulating) {
            runCatching { accumulator.message() }
                .onFailure { log.warn("conversation {} paused reply could not be rebuilt: {}", conversationId, it.toString()) }
                .getOrNull()
        } else null
        return StreamResult(stopReason, message)
    }

    /** 답변 끝에 붙이는 출처 목록. 웹 검색 결과를 보여줄 때는 출처를 함께 표시해야 한다 */
    private fun sourcesFooter(sources: Collection<Source>): String =
        sources.take(MAX_SOURCES).joinToString("\n", prefix = "\n\n**출처**\n") { source ->
            val title = (source.title?.takeIf { it.isNotBlank() } ?: source.url)
                .replace("[", "(").replace("]", ")")
            "- [$title](${source.url})"
        }

    private fun buildParams(prepared: PreparedChat): MessageCreateParams =
        MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .system(SYSTEM_PROMPT.trimIndent() + "\n\n" + context.forClaude(prepared.conversationId))
            .tools(WEB_TOOLS + tools.claudeDefinitions.map { ToolUnion.ofTool(it) })
            .apply {
                prepared.history.forEach {
                    if (it.role == "user") addUserMessage(it.content) else addAssistantMessage(it.content)
                }
            }
            .build()

    /** 응답 도중 대화가 삭제됐으면 저장하지 않고 false를 돌려준다 */
    private fun saveAssistant(conversationId: UUID, text: String, status: MessageStatus): Boolean =
        tx.execute {
            val conversation = conversations.findForUpdate(conversationId) ?: return@execute false
            messages.save(ChatMessage(conversationId, "assistant", text, status))
            conversation.updatedAt = OffsetDateTime.now()
            conversations.save(conversation)
            true
        } == true

    /** 답을 하나도 받지 못한 질문을 지운다. 이번 요청으로 새로 만든 대화면 대화째 지운다 */
    private fun discardUserMessage(prepared: PreparedChat) {
        tx.executeWithoutResult {
            messages.deleteById(prepared.userMessageId)
            if (prepared.isNewConversation) conversations.deleteById(prepared.conversationId)
        }
    }
}
