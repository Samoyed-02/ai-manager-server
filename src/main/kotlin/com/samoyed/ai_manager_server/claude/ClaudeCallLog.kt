package com.samoyed.ai_manager_server.claude

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.UUID

/**
 * Claude 호출 한 번의 결과. 잘림·거절 빈도, 입력 길이, 비용을 판단하는 근거로 쓴다.
 * 프롬프트와 응답 본문은 사용자 프로필·대화 내용이 들어 있어 남기지 않는다.
 * 응답을 받지 못해 알 수 없는 값은 null이다.
 */
data class ClaudeCall(
    val kind: String,
    val id: UUID,
    val model: String,
    val stopReason: StopReason?,
    val inputTokens: Long?,
    val outputTokens: Long?,
    val cacheReadTokens: Long?,
    val cacheCreationTokens: Long?,
    val durationMs: Long,
    val historyMessages: Int? = null,
    val webSearchRequests: Long? = null,
    val webFetchRequests: Long? = null,
    val continuations: Int? = null,
    val toolCalls: Int? = null,
    val error: ClaudeErrorKind? = null,
)

private val usageLog = LoggerFactory.getLogger("claude.usage")

/** System.nanoTime()으로 잰 시작 시각부터 지금까지 걸린 밀리초 */
fun elapsedMs(startedAtNanos: Long): Long = (System.nanoTime() - startedAtNanos) / 1_000_000

/**
 * 호출마다 `claude.usage` 로거에 한 줄을 남긴다.
 * 같은 값을 key-value로도 넣어, 구조화 로그(logging.structured.format.console)를 켜면 JSON 필드가 된다.
 */
fun logClaudeCall(call: ClaudeCall) {
    val fields = buildList {
        add("kind" to call.kind)
        add("id" to call.id)
        add("model" to call.model)
        add("stop_reason" to call.stopReason)
        add("input_tokens" to call.inputTokens)
        add("output_tokens" to call.outputTokens)
        add("cache_read_tokens" to call.cacheReadTokens)
        add("cache_creation_tokens" to call.cacheCreationTokens)
        add("duration_ms" to call.durationMs)
        if (call.historyMessages != null) add("history_messages" to call.historyMessages)
        if (call.webSearchRequests != null) add("web_search_requests" to call.webSearchRequests)
        if (call.webFetchRequests != null) add("web_fetch_requests" to call.webFetchRequests)
        if (call.continuations != null) add("continuations" to call.continuations)
        if (call.toolCalls != null) add("tool_calls" to call.toolCalls)
        add("error" to call.error)
    }
    var event = usageLog.atInfo()
    // 숫자는 그대로 넘겨 JSON에서도 숫자로 집계할 수 있게 한다
    fields.forEach { (key, value) ->
        if (value != null) event = event.addKeyValue(key, if (value is Number) value else value.toString())
    }
    event.log("claude call " + fields.joinToString(" ") { (key, value) -> "$key=${value ?: "-"}" })
}

/**
 * 스트리밍이 아닌 Claude 호출 한 번을 타임아웃·재시도 제한과 함께 하고 사용량 로그를 남긴다.
 * 실패해도 로그(error 종류)를 남기고 예외는 그대로 던진다
 */
fun AnthropicClient.createLogged(
    params: MessageCreateParams,
    kind: String,
    id: UUID,
    model: String,
    timeout: Duration,
    maxRetries: Int = 1,
): Message {
    val startedAt = System.nanoTime()
    val message = try {
        withOptions { it.timeout(timeout).maxRetries(maxRetries) }.messages().create(params)
    } catch (e: Exception) {
        logClaudeCall(
            ClaudeCall(
                kind = kind, id = id, model = model, stopReason = null,
                inputTokens = null, outputTokens = null, cacheReadTokens = null, cacheCreationTokens = null,
                durationMs = elapsedMs(startedAt), error = classifyClaudeError(e),
            )
        )
        throw e
    }
    val usage = message.usage()
    logClaudeCall(
        ClaudeCall(
            kind = kind, id = id, model = model, stopReason = message.stopReason().orElse(null),
            inputTokens = usage.inputTokens(), outputTokens = usage.outputTokens(),
            cacheReadTokens = usage.cacheReadInputTokens().orElse(null),
            cacheCreationTokens = usage.cacheCreationInputTokens().orElse(null),
            durationMs = elapsedMs(startedAt),
        )
    )
    return message
}

/** 응답의 텍스트 블록만 이어 붙인다 (thinking 등 다른 블록은 건너뛴다) */
fun Message.text(): String = content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
