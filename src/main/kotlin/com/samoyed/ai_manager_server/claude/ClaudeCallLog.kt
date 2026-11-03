package com.samoyed.ai_manager_server.claude

import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
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
        add("error" to call.error)
    }
    var event = usageLog.atInfo()
    // 숫자는 그대로 넘겨 JSON에서도 숫자로 집계할 수 있게 한다
    fields.forEach { (key, value) ->
        if (value != null) event = event.addKeyValue(key, if (value is Number) value else value.toString())
    }
    event.log("claude call " + fields.joinToString(" ") { (key, value) -> "$key=${value ?: "-"}" })
}
