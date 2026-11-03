package com.samoyed.ai_manager_server.tool

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.Tool
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/** 도구 목록과 실행. 채팅은 Claude 도구 정의로, 통화는 /tools API로 같은 도구를 쓴다 */
@Component
class ToolRegistry(tools: List<AgentTool>) {
    private val log = LoggerFactory.getLogger(javaClass)

    val tools: List<AgentTool> = tools.sortedBy { it.name }
    private val byName = this.tools.associateBy { it.name }

    init {
        require(byName.size == tools.size) { "duplicate tool names: ${tools.map { it.name }}" }
    }

    fun find(name: String): AgentTool? = byName[name]

    /** Claude 요청에 넣을 도구 정의. 스트리밍 요청이라 입력을 버퍼링 없이 받는다 */
    val claudeDefinitions: List<Tool> by lazy {
        this.tools.map { tool ->
            val schema = tool.inputSchema
            Tool.builder()
                .name(tool.name)
                .description(tool.description)
                .inputSchema(
                    Tool.InputSchema.builder()
                        .properties(
                            Tool.InputSchema.Properties.builder()
                                .putAllAdditionalProperties(
                                    @Suppress("UNCHECKED_CAST")
                                    (schema["properties"] as Map<String, Any>).mapValues { JsonValue.from(it.value) }
                                )
                                .build()
                        )
                        .required(@Suppress("UNCHECKED_CAST") (schema["required"] as List<String>))
                        .build()
                )
                .eagerInputStreaming(true)
                .build()
        }
    }

    /**
     * 도구를 실행한다. 입력 오류나 실패는 예외 대신 ok=false 결과로 돌려줘, 모델이 사유를 보고 고치거나 사용자에게 설명하게 한다.
     * usedInReply: 이번 채팅 답변에서 도구별로 실행한 횟수. 통화처럼 답변 단위가 없으면 빈 맵을 넘긴다
     */
    fun execute(name: String, input: Map<String, Any?>?, ctx: ToolContext, usedInReply: MutableMap<String, Int>): ToolResult {
        val tool = find(name) ?: return ToolResult.error("알 수 없는 도구: $name")
        if (input == null) return ToolResult.error("입력을 읽을 수 없어. JSON 객체로 보내.")
        val used = usedInReply.getOrDefault(name, 0)
        tool.maxPerReply?.let { max ->
            if (used >= max) return ToolResult.error("이번 답변에서 $name 은(는) 최대 ${max}번까지야. 더 부르지 말고 사용자에게 알려.")
        }
        return try {
            tool.run(ToolInput(input), ctx).also {
                if (it.ok) usedInReply[name] = used + 1
                log.info("tool {} via {} (conversation {}) ok={}", name, ctx.channel, ctx.conversationId, it.ok)
            }
        } catch (e: ToolInputException) {
            ToolResult.error(e.message ?: "입력이 잘못됐어.")
        } catch (e: Exception) {
            log.error("tool {} failed", name, e)
            ToolResult.error("도구 실행 중 서버 오류가 났어. 사용자에게 잠시 후 다시 시도해 달라고 알려.")
        }
    }
}
