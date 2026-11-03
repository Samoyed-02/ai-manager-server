package com.samoyed.ai_manager_server.chat

import com.anthropic.core.JsonValue
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.Tool
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.models.messages.ToolUseBlock
import com.fasterxml.jackson.core.type.TypeReference
import com.samoyed.ai_manager_server.task.MAX_TASK_CONTEXT
import com.samoyed.ai_manager_server.task.MAX_TASK_INSTRUCTION
import com.samoyed.ai_manager_server.task.Task
import com.samoyed.ai_manager_server.task.TaskRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.UUID

const val CREATE_TASK_TOOL = "create_task"

/** 도구 실행 결과. Claude에게 돌려줄 tool_result와, 만들었으면 그 작업 */
data class ToolOutcome(val result: ContentBlockParam, val task: Task? = null)

/**
 * 채팅에서 문서 작성 작업(Task)을 맡기는 도구. 우리 서버가 실행한다.
 * 작업 작성자(TaskWorker)는 대화를 볼 수 없으므로 필요한 내용은 context에 담게 한다
 */
@Component
class CreateTaskTool(private val tasks: TaskRepository) {
    private val log = LoggerFactory.getLogger(javaClass)

    val definition: Tool = Tool.builder()
        .name(CREATE_TASK_TOOL)
        .description(
            """
            문서 작성 작업을 백그라운드 작성자에게 맡긴다. 자기소개서, 보고서, 계획서처럼 긴 문서를 써 달라고 사용자가 분명히 요청했을 때만 호출해.
            짧은 답이나 대화 중 설명은 직접 답하고, 사용자가 원하는지 애매하면 호출하지 말고 먼저 물어봐.
            작성자는 이 대화를 볼 수 없으니, 대화에서 나온 요구사항·배경·사실을 context에 빠짐없이 담아.
            작업은 바로 만들어지고 몇 분 안에 문서가 완성된다.
            """.trimIndent()
        )
        .inputSchema(
            Tool.InputSchema.builder()
                .properties(
                    Tool.InputSchema.Properties.builder()
                        .putAdditionalProperty(
                            "instruction",
                            JsonValue.from(
                                mapOf(
                                    "type" to "string",
                                    "description" to "작성할 문서와 요구사항. 예: 'OO기업 백엔드 신입 지원용 자기소개서 1번 문항 초안'",
                                    "maxLength" to MAX_TASK_INSTRUCTION,
                                )
                            ),
                        )
                        .putAdditionalProperty(
                            "context",
                            JsonValue.from(
                                mapOf(
                                    "type" to "string",
                                    "description" to "작성자가 알아야 할 대화 내용, 배경, 사실. 작성자는 대화를 볼 수 없다",
                                    "maxLength" to MAX_TASK_CONTEXT,
                                )
                            ),
                        )
                        .build()
                )
                .required(listOf("instruction"))
                .build()
        )
        // 스트리밍 요청이라 입력을 버퍼링 없이 받는다. 대신 API가 검증하지 않으므로 run()에서 직접 검증한다
        .eagerInputStreaming(true)
        .build()

    /** alreadyCreated: 이번 답변에서 이미 작업을 만들었으면 true. 답변 하나에 작업은 하나만 만든다 */
    fun run(block: ToolUseBlock, conversationId: UUID, alreadyCreated: Boolean): ToolOutcome {
        if (alreadyCreated) return error(block, "이번 답변에서 이미 작업을 하나 만들었어. 더 만들지 말고 사용자에게 알려.")

        val input = runCatching { block._input().convert(object : TypeReference<Map<String, Any?>>() {}) }.getOrNull()
            ?: return error(block, "입력을 읽을 수 없어. instruction(문자열)과 context(문자열, 선택)를 JSON 객체로 보내.")
        val instruction = input["instruction"] as? String
        val context = input["context"]
        when {
            instruction.isNullOrBlank() -> return error(block, "instruction이 비어 있어.")
            instruction.length > MAX_TASK_INSTRUCTION -> return error(block, "instruction은 최대 ${MAX_TASK_INSTRUCTION}자야.")
            context != null && context !is String -> return error(block, "context는 문자열이어야 해.")
            context is String && context.length > MAX_TASK_CONTEXT -> return error(block, "context는 최대 ${MAX_TASK_CONTEXT}자야.")
        }

        val task = tasks.save(Task("document", instruction!!, (context as String?)?.takeIf { it.isNotBlank() }))
        log.info("conversation {} created task {} via {}", conversationId, task.id, CREATE_TASK_TOOL)
        return ToolOutcome(result(block, """{"task_id":"${task.id}","status":"PENDING"}""", isError = false), task)
    }

    /** 처리하지 않는 도구 호출에도 tool_result는 반드시 돌려줘야 한다 */
    fun unknown(block: ToolUseBlock): ToolOutcome = error(block, "알 수 없는 도구: ${block.name()}")

    private fun error(block: ToolUseBlock, message: String) = ToolOutcome(result(block, message, isError = true))

    private fun result(block: ToolUseBlock, content: String, isError: Boolean): ContentBlockParam =
        ContentBlockParam.ofToolResult(
            ToolResultBlockParam.builder().toolUseId(block.id()).content(content).isError(isError).build()
        )
}
