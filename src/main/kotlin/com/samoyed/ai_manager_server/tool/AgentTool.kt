package com.samoyed.ai_manager_server.tool

import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.task.Task
import java.time.LocalDate
import java.time.LocalTime
import java.util.UUID

/** 도구를 부른 곳. 통화 중에는 대화가 아직 저장되지 않아 conversationId가 없다 */
data class ToolContext(val channel: ConversationChannel, val conversationId: UUID?)

/**
 * 도구 실행 결과. content는 모델에게 돌려줄 내용(JSON이나 문장).
 * summary는 사용자에게 보여줄 한 줄, task는 create_task가 만든 작업
 */
data class ToolResult(val ok: Boolean, val content: String, val summary: String? = null, val task: Task? = null) {
    companion object {
        fun error(message: String) = ToolResult(ok = false, content = message)
    }
}

/** 입력이 잘못됐다. message는 모델이 고쳐서 다시 부를 수 있게 구체적으로 쓴다 */
class ToolInputException(message: String) : RuntimeException(message)

/**
 * 채팅(Claude)과 통화(Gemini)가 함께 쓰는 도구. 우리 서버가 실행한다.
 * 스트리밍 요청에서 eager_input_streaming을 켜므로 API가 입력을 검증하지 않는다. run()에서 ToolInput으로 직접 검증한다
 */
interface AgentTool {
    val name: String
    val description: String
    /** JSON Schema object. {"type":"object","properties":{...},"required":[...]} */
    val inputSchema: Map<String, Any>
    /** 채팅 답변 하나에서 실행할 수 있는 횟수. null이면 제한 없음 */
    val maxPerReply: Int?
    fun run(input: ToolInput, ctx: ToolContext): ToolResult
}

/** 스키마 작성 도우미 */
fun objectSchema(required: List<String>, vararg properties: Pair<String, Map<String, Any>>): Map<String, Any> =
    mapOf("type" to "object", "properties" to mapOf(*properties), "required" to required)

fun stringProp(description: String, maxLength: Int? = null, enum: List<String>? = null, pattern: String? = null): Map<String, Any> =
    buildMap {
        put("type", "string")
        put("description", description)
        maxLength?.let { put("maxLength", it) }
        enum?.let { put("enum", it) }
        pattern?.let { put("pattern", it) }
    }

/** 모델이 보낸 입력을 꺼내며 검증한다. 잘못되면 ToolInputException */
class ToolInput(private val values: Map<String, Any?>) {

    fun string(name: String, max: Int): String =
        optionalString(name, max) ?: throw ToolInputException("$name 은(는) 필수야.")

    fun optionalString(name: String, max: Int): String? {
        val value = values[name] ?: return null
        if (value !is String) throw ToolInputException("$name 은(는) 문자열이어야 해.")
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return null
        if (trimmed.length > max) throw ToolInputException("$name 은(는) 최대 ${max}자야.")
        return trimmed
    }

    inline fun <reified E : Enum<E>> enum(name: String): E =
        optionalEnum<E>(name) ?: throw ToolInputException("$name 은(는) 필수야. 가능한 값: ${enumValues<E>().joinToString { it.name }}")

    inline fun <reified E : Enum<E>> optionalEnum(name: String): E? {
        val raw = optionalString(name, 50) ?: return null
        return enumValues<E>().firstOrNull { it.name == raw.uppercase() }
            ?: throw ToolInputException("$name 값 '$raw'은(는) 쓸 수 없어. 가능한 값: ${enumValues<E>().joinToString { it.name }}")
    }

    fun optionalInt(name: String, range: IntRange): Int? {
        val value = values[name] ?: return null
        val number = (value as? Number)?.toDouble() ?: throw ToolInputException("$name 은(는) 숫자여야 해.")
        if (number % 1.0 != 0.0 || number.toInt() !in range) throw ToolInputException("$name 은(는) ${range.first}~${range.last} 사이 정수여야 해.")
        return number.toInt()
    }

    fun optionalBoolean(name: String): Boolean? {
        val value = values[name] ?: return null
        return value as? Boolean ?: throw ToolInputException("$name 은(는) true/false여야 해.")
    }

    fun uuid(name: String): UUID {
        val raw = string(name, 36)
        return runCatching { UUID.fromString(raw) }.getOrNull() ?: throw ToolInputException("$name 은(는) UUID여야 해. 목록 도구로 id를 먼저 확인해.")
    }

    /** "HH:mm" (24시간) */
    fun optionalTime(name: String): LocalTime? {
        val raw = optionalString(name, 5) ?: return null
        val match = Regex("""(\d{1,2}):(\d{2})""").matchEntire(raw) ?: throw ToolInputException("$name 은(는) HH:mm(24시간) 형식이어야 해.")
        val (h, m) = match.destructured
        if (h.toInt() !in 0..23 || m.toInt() !in 0..59) throw ToolInputException("$name 은(는) 00:00~23:59 사이여야 해.")
        return LocalTime.of(h.toInt(), m.toInt())
    }

    /** "YYYY-MM-DD" */
    fun optionalDate(name: String): LocalDate? {
        val raw = optionalString(name, 10) ?: return null
        return runCatching { LocalDate.parse(raw) }.getOrNull() ?: throw ToolInputException("$name 은(는) YYYY-MM-DD 형식이어야 해.")
    }

    /** 문자열 배열. 키가 없으면 null, 빈 배열이면 빈 리스트 */
    fun optionalStringList(name: String): List<String>? {
        val value = values[name] ?: return null
        if (value !is List<*> || value.any { it !is String }) throw ToolInputException("$name 은(는) 문자열 배열이어야 해.")
        return value.map { (it as String).trim() }
    }
}
