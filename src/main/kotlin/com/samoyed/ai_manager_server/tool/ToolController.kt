package com.samoyed.ai_manager_server.tool

import com.samoyed.ai_manager_server.chat.ConversationChannel
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException

data class ToolDeclaration(val name: String, val description: String, val parameters: Map<String, Any>)
data class ToolCallRequest(val args: Map<String, Any?> = emptyMap())
data class ToolCallResponse(val ok: Boolean, val result: String?, val error: String?, val summary: String?)

/**
 * 통화(Gemini)용 도구 중계. 클라이언트가 목록을 함수 선언으로 등록하고, 함수 호출이 오면 여기로 보낸 뒤 결과를 Gemini에 돌려준다.
 * 도구 실패(입력 오류 등)는 200 + ok=false로 돌려줘 모델이 사유를 보고 사용자에게 설명하게 한다
 */
@RestController
@RequestMapping("/tools")
class ToolController(private val registry: ToolRegistry) {

    @GetMapping
    fun list(): List<ToolDeclaration> = registry.tools.map { ToolDeclaration(it.name, it.description, it.inputSchema) }

    @PostMapping("/{name}")
    fun call(@PathVariable name: String, @RequestBody(required = false) req: ToolCallRequest?): ToolCallResponse {
        if (registry.find(name) == null) throw ResponseStatusException(HttpStatus.NOT_FOUND)
        // 통화에는 채팅처럼 답변 단위가 없어 호출마다 새로 센다
        val result = registry.execute(name, req?.args ?: emptyMap(), ToolContext(ConversationChannel.VOICE, null), mutableMapOf())
        return if (result.ok) ToolCallResponse(true, result.content, null, result.summary)
        else ToolCallResponse(false, null, result.content, null)
    }
}
