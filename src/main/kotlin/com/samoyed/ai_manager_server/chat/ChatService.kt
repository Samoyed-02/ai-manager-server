package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.CacheControlEphemeral
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.TextBlockParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.fasterxml.jackson.core.type.TypeReference
import com.samoyed.ai_manager_server.tool.ToolContext
import com.samoyed.ai_manager_server.tool.ToolRegistry
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.helpers.MessageAccumulator
import com.anthropic.models.messages.RawContentBlockStartEvent
import com.anthropic.models.messages.RawMessageStreamEvent
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
검색은 질문당 6번까지야. 한도에 걸리면 이미 찾은 결과로 답하고, 확인하지 못한 부분만 말해.
웹 검색이나 코드 실행 전에는 글을 쓰지 말고, 결과를 다 본 뒤 한 번에 답해.
출처와 링크는 답변 끝에 자동으로 붙어. 답변에서는 링크를 쓰거나 출처를 어떻게 붙이는지 설명하지 마.
말로 요청한 일은 도구로 처리해: 긴 문서 작성은 create_task, "기억해"는 save_memory, 반복 알림·브리핑은 루틴 도구, 한 일은 log_activity.
도구로 처리했으면 무엇을 했는지 짧게 알려 주고, 삭제처럼 되돌릴 수 없는 일은 먼저 확인받아.
이전 답변 앞의 [서버 기록]은 그 답변에서 서버가 실제로 실행한 작업이야. 직접 쓰지 마.
[서버 기록]이 없는 예전 답변도 하지 않았다고 단정하지 말고, 필요하면 조회 도구로 확인해.
"""

/** 캐시 유지 5분. 한 턴의 도구 라운드와 연달아 주고받는 대화가 읽는다 */
private val CACHE: CacheControlEphemeral = CacheControlEphemeral.builder().ttl(CacheControlEphemeral.Ttl.TTL_5M).build()

/** 이전 답변 앞에 붙여 모델에게 보여 주는 실행 기록의 머리말 */
private const val ACTIONS_HEADER = "[서버 기록: 이 답변에서 실제로 실행한 작업]"
private const val MAX_ACTION_DETAIL = 100

/**
 * 질문 하나에 쓸 수 있는 웹 검색·페이지 읽기 횟수. 검색은 1,000회당 $10이라 질문당 최대 $0.06.
 * 동적 필터링은 코드 한 번 안에서 검색을 여러 번 부르므로 3회로는 뉴스 요약도 실패했다
 */
private const val MAX_WEB_SEARCHES = 6L
private const val MAX_WEB_FETCHES = 3L
/** 페이지 하나를 읽을 때 컨텍스트에 넣을 최대 토큰. 큰 문서가 입력 토큰을 크게 늘리지 않게 한다 */
private const val MAX_FETCH_CONTENT_TOKENS = 20_000L
/** 서버 쪽 도구 반복이 한도에 걸려 pause_turn으로 끝나면 이어 받는 최대 횟수 */
private const val MAX_CONTINUATIONS = 3
/** 우리 서버가 실행하는 도구의 결과를 돌려주고 이어 받는 최대 횟수. 목록을 본 뒤 수정하는 식으로 연달아 쓸 수 있게 한다 */
private const val MAX_TOOL_ROUNDS = 4
private const val MAX_SOURCES = 10
/** 인용이 하나도 없을 때 대신 보여 줄 검색 결과의 최대 수 */
private const val MAX_SEARCH_RESULT_SOURCES = 5

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
    /**
     * true면 매번 바뀌는 맥락(시각, 기억, 활동…)을 현재 사용자 메시지 뒤의 system 메시지로 보낸다.
     * 맥락이 대화 기록 뒤에 오므로 기록까지 캐시된다. 모델이 대화 중 system 메시지를 받지 않으면 false로 되돌린다
     */
    @Value("\${claude.context-as-system-message:true}") private val contextAsSystemMessage: Boolean = true,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val blockLog = LoggerFactory.getLogger("chat.blocks")

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
        val searchResults = LinkedHashMap<String, Source>()
        var stopReason: StopReason? = null
        var failure: Exception? = null
        // 토큰·도구 사용량은 요청마다 받아 합친다. pause_turn으로 이어 받으면 요청이 여러 번이 된다
        val usage = UsageTotals()
        var continuations = 0
        var toolRounds = 0
        var toolCalls = 0
        val usedInReply = mutableMapOf<String, Int>()
        val actions = mutableListOf<String>()
        val toolContext = ToolContext(ConversationChannel.CHAT, prepared.conversationId)
        var params = buildParams(prepared)
        val startedAt = System.nanoTime()
        val output = InterimTextFilter { delta ->
            text.append(delta)
            emit(ChatEvent.Delta(delta))
        }
        try {
            while (true) {
                val result = streamOnce(params, usage, sources, searchResults, prepared.conversationId, output)
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
                            actions += when {
                                outcome.task != null -> "문서 작업 생성: ${outcome.task.instruction.oneLine()}"
                                !outcome.ok -> "실패: ${block.name()} (${outcome.content.oneLine()})"
                                outcome.summary != null -> outcome.summary.oneLine()
                                else -> "조회: ${block.name()}"
                            }
                            ContentBlockParam.ofToolResult(
                                ToolResultBlockParam.builder()
                                    .toolUseId(block.id()).content(outcome.content).isError(!outcome.ok)
                                    .build()
                            )
                        }
                        if (results.isEmpty()) break
                        // 같은 턴의 다음 요청은 자동 캐시 지점(마지막 블록)으로 맥락·도구 결과까지 캐시에서 읽는다
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
        // 실패로 끊긴 응답의 모아 둔 텍스트도 보낸다. 받은 데까지 INTERRUPTED로 저장된다
        output.responseEnd()
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

        // 동적 필터링 검색은 코드로 결과를 걸러 쓰느라 인용 없이 답할 때가 있다. 그때는 검색한 페이지 중 답변과 겹치는 것을 대신 보여 준다
        val footer = when {
            text.isBlank() -> null
            sources.isNotEmpty() -> sourcesFooter("출처", sources.values.take(MAX_SOURCES))
            searchResults.isNotEmpty() ->
                sourcesFooter("검색한 페이지", pickSearchResults(text.toString(), searchResults.values, MAX_SEARCH_RESULT_SOURCES))
            else -> null
        }
        if (footer != null) {
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

        usage.webSearchRequests?.takeIf { it > 0 }?.let { actions += "웹 검색 ${it}회" }
        usage.webFetchRequests?.takeIf { it > 0 }?.let { actions += "웹 페이지 읽기 ${it}회" }

        if (!saveAssistant(prepared.conversationId, text.toString(), status, actions)) {
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
     * 요청 한 번을 스트리밍한다. 텍스트 조각은 textFilter로 넘겨 서버 도구 앞의 진행 문장을 거르고, 출처와 사용량을 모은다.
     * pause_turn이면 이어 보내야 하므로 응답 전체를 MessageAccumulator로 모아 돌려준다.
     */
    private fun streamOnce(
        params: MessageCreateParams,
        usage: UsageTotals,
        sources: MutableMap<String, Source>,
        searchResults: MutableMap<String, Source>,
        conversationId: UUID,
        textFilter: InterimTextFilter,
    ): StreamResult {
        val accumulator = MessageAccumulator.create()
        var accumulating = true
        // 모으기에 실패해도 답변은 계속 받는다. 이어 받기만 못 하게 된다
        fun accumulate(event: RawMessageStreamEvent) {
            if (!accumulating) return
            try {
                accumulator.accumulate(event)
            } catch (e: Exception) {
                accumulating = false
                log.warn("conversation {} could not accumulate stream, pause_turn continuation disabled: {}", conversationId, e.toString())
            }
        }
        // 입력 조각(input_json_delta)을 아직 받지 못한 도구 블록의 시작 입력. 조각 없이 끝나면 이 입력으로 대신한다
        val pendingToolInputs = mutableMapOf<Long, JsonValue>()
        val blocks = mutableListOf<String>()
        // 진단 로그에 텍스트 블록의 길이와 버림 여부를 남긴다
        var textLabel = -1
        var textLength = 0
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
                    // MessageAccumulator는 도구 블록에 입력 조각이 하나도 없으면 실패한다.
                    // 동적 필터링 웹 검색은 서버 도구 블록을 조각 없이 보내므로, 시작 때 받은 입력을 조각으로 넣어 준다
                    event.contentBlockStop().ifPresent { stop ->
                        pendingToolInputs.remove(stop.index())?.let { accumulate(inputJsonDelta(stop.index(), it)) }
                    }
                    accumulate(event)
                    event.messageStart().ifPresent {
                        val u = it.message().usage()
                        input = u.inputTokens()
                        cacheRead = u.cacheReadInputTokens().orElse(null)
                        cacheCreation = u.cacheCreationInputTokens().orElse(null)
                    }
                    event.contentBlockStart().ifPresent { start ->
                        val block = start.contentBlock()
                        if (textLabel >= 0) blocks[textLabel] = "text:$textLength"
                        textLabel = -1
                        when {
                            block.isText() -> { textFilter.textStart(); textLabel = blocks.size; textLength = 0 }
                            block.isToolUse() || block.isServerToolUse() ->
                                textFilter.toolStart(serverTool = block.isServerToolUse())?.let { dropped ->
                                    blocks[blocks.size - 1] = "text:$dropped:dropped"
                                }
                            else -> textFilter.otherStart()
                        }
                        blocks += blockLabel(block)
                        block.toolUse().ifPresent { pendingToolInputs[start.index()] = it._input() }
                        block.serverToolUse().ifPresent { pendingToolInputs[start.index()] = it._input() }
                        block.webFetchToolResult()
                            .flatMap { it.content().webFetchBlock() }
                            .ifPresent { sources.putIfAbsent(it.url(), Source(it.url(), it.content().title().orElse(null))) }
                        block.webSearchToolResult()
                            .flatMap { it.content().resultBlocks() }
                            .ifPresent { results -> results.forEach { searchResults.putIfAbsent(it.url(), Source(it.url(), it.title())) } }
                    }
                    event.contentBlockDelta().ifPresent { blockDelta ->
                        if (blockDelta.delta().isInputJson()) pendingToolInputs.remove(blockDelta.index())
                        blockDelta.delta().text().ifPresent { textLength += it.text().length; textFilter.text(it.text()) }
                        blockDelta.delta().citations()
                            .flatMap { it.citation().webSearchResultLocation() }
                            .ifPresent { textFilter.cited(); sources.putIfAbsent(it.url(), Source(it.url(), it.title().orElse(null))) }
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
            textFilter.responseEnd()
        } finally {
            if (textLabel >= 0) blocks[textLabel] = "text:$textLength"
            blockLog.debug("conversation {} stop_reason={} blocks={}", conversationId, stopReason, blocks)
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

    /** 시작 때 받은 도구 입력 전체를 조각 하나로 만든 이벤트 */
    private fun inputJsonDelta(index: Long, input: JsonValue): RawMessageStreamEvent {
        val partialJson = jsonMapper().writeValueAsString(input).takeUnless { it == "null" } ?: "{}"
        return jsonMapper().readValue(
            """{"type":"content_block_delta","index":$index,"delta":{"type":"input_json_delta","partial_json":${jsonMapper().writeValueAsString(partialJson)}}}""",
            RawMessageStreamEvent::class.java,
        )
    }

    /** 진단용 블록 이름. 실제 응답 형태가 바뀌었을 때 chat.blocks 로그로 확인한다 */
    private fun blockLabel(block: RawContentBlockStartEvent.ContentBlock): String = when {
        block.isText() -> "text"
        block.isThinking() -> "thinking"
        block.isRedactedThinking() -> "redacted_thinking"
        block.isToolUse() -> "tool_use:" + block.asToolUse().name()
        block.isServerToolUse() -> "server_tool_use:" + block.asServerToolUse().name()
        block.isWebSearchToolResult() -> block.asWebSearchToolResult().content().let { content ->
            "web_search_tool_result:" + (content.resultBlocks().map { "${it.size}" }.orElse(null) ?: content.error().map { "error=${it.errorCode()}" }.orElse("?"))
        }
        block.isWebFetchToolResult() -> "web_fetch_tool_result"
        block.isCodeExecutionToolResult() -> block.asCodeExecutionToolResult().content().let { content ->
            "code_execution_tool_result:" + when {
                content.isError() -> "error=${content.asError().errorCode()}"
                content.isResultBlock() -> "rc=${content.asResultBlock().returnCode()}"
                else -> "encrypted"
            }
        }
        block.isBashCodeExecutionToolResult() -> "bash_code_execution_tool_result"
        block.isTextEditorCodeExecutionToolResult() -> "text_editor_code_execution_tool_result"
        else -> "other"
    }

    /** 답변 끝에 붙이는 출처 목록. 웹 검색 결과를 보여줄 때는 출처를 함께 표시해야 한다 */
    private fun sourcesFooter(heading: String, sources: Collection<Source>): String =
        sources.joinToString("\n", prefix = "\n\n**$heading**\n") { source ->
            val title = (source.title?.takeIf { it.isNotBlank() } ?: source.url)
                .replace("[", "(").replace("]", ")")
            "- [$title](${source.url})"
        }

    /**
     * 캐시는 tools → system → messages 순서의 앞부분이 똑같을 때만 읽힌다. 바뀌지 않는 것을 앞에, 바뀌는 것을 뒤에 둔다.
     * ① 고정 지시(앞의 도구 정의 포함) ② 현재 사용자 메시지에 캐시 지점을 두고, 매 턴 바뀌는 맥락은 그 뒤에 보낸다.
     * 다음 턴에는 이번 ②까지가 그대로 앞부분이 되어 대화 기록 전체를 캐시에서 읽는다.
     * 같은 턴의 도구 라운드는 자동 캐시 지점(요청의 마지막 블록)으로 맥락·도구 결과까지 읽는다
     */
    private fun buildParams(prepared: PreparedChat): MessageCreateParams {
        val context = context.forClaude(prepared.conversationId)
        val system = buildList {
            add(TextBlockParam.builder().text(SYSTEM_PROMPT.trimIndent()).cacheControl(CACHE).build())
            if (!contextAsSystemMessage) add(TextBlockParam.builder().text(context).build())
        }
        return MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .systemOfTextBlockParams(system)
            .tools(WEB_TOOLS + tools.claudeDefinitions.map { ToolUnion.ofTool(it) })
            .cacheControl(CACHE)
            .apply {
                prepared.history.forEachIndexed { i, message ->
                    when {
                        message.role != "user" -> addAssistantMessage(withActions(message))
                        i == prepared.history.lastIndex -> addUserMessageOfBlockParams(
                            listOf(ContentBlockParam.ofText(TextBlockParam.builder().text(message.content).cacheControl(CACHE).build()))
                        )
                        else -> addUserMessage(message.content)
                    }
                }
                if (contextAsSystemMessage) addSystemMessage(context)
            }
            .build()
    }

    /**
     * 답변 글만 다시 보내면 모델은 "등록했어요" 같은 말의 근거를 보지 못해 지어낸 말로 여기고 정정한다.
     * 실제로 실행한 작업을 답변 앞에 붙여 근거로 보여 준다
     */
    private fun withActions(message: ChatMessage): String =
        message.actions?.takeIf { it.isNotBlank() }
            ?.let { actions -> actions.lines().joinToString("\n", prefix = "$ACTIONS_HEADER\n", postfix = "\n\n") { "- $it" } + message.content }
            ?: message.content

    private fun String.oneLine(): String =
        replace(Regex("\\s+"), " ").trim().let { if (it.length > MAX_ACTION_DETAIL) it.take(MAX_ACTION_DETAIL) + "…" else it }

    /** 응답 도중 대화가 삭제됐으면 저장하지 않고 false를 돌려준다 */
    private fun saveAssistant(conversationId: UUID, text: String, status: MessageStatus, actions: List<String>): Boolean =
        tx.execute {
            val conversation = conversations.findForUpdate(conversationId) ?: return@execute false
            messages.save(ChatMessage(conversationId, "assistant", text, status, actions.takeIf { it.isNotEmpty() }?.joinToString("\n")))
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
