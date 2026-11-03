package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.claude.ClaudeCallLogCapture
import com.samoyed.ai_manager_server.task.TaskRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/** 채팅에서 create_task 도구로 문서 작성 작업을 맡기는 흐름 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatCreateTaskToolTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService
    @Autowired lateinit var messages: ChatMessageRepository
    @Autowired lateinit var tasks: TaskRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val events = mutableListOf<ChatEvent>()

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
        jdbc.sql("delete from documents").update()
        jdbc.sql("delete from tasks").update()
        `when`(client.messages()).thenReturn(messageService)
        events.clear()
    }

    @Test
    fun `요청에 create_task 도구가 eager_input_streaming과 함께 들어간다`() {
        val sent = messageService.streamsInOrder(chatStream { start(); text("답"); end("end_turn") })

        chat.respond(chat.prepare(null, "질문")) { events += it }

        val tool = sent.single().tools().get().filter { it.isTool() }.map { it.asTool() }.single { it.name() == "create_task" }
        assertAll(
            { assertEquals("create_task", tool.name()) },
            { assertEquals(true, tool.eagerInputStreaming().get()) },
            { assertEquals(listOf("instruction"), tool.inputSchema().required().get()) },
        )
    }

    @Test
    fun `create_task를 부르면 작업을 만들고 결과를 돌려준 뒤 이어서 답한다`() {
        val prepared = chat.prepare(null, "OO기업 자소서 초안 맡겨줘")
        var toolUseId = ""
        val sent = messageService.streamsInOrder(
            chatStream {
                start()
                text("작성자에게 맡길게요.")
                toolUseId = toolUse("create_task", mapOf("instruction" to "OO기업 자소서 초안", "context" to "백엔드 신입, 성장 경험 강조"))
                end("tool_use")
            },
            chatStream { start(); text(" 맡겼어요. 완성되면 문서 목록에서 볼 수 있어요."); end("end_turn") },
        )

        val (line, _) = ClaudeCallLogCapture().use { log ->
            chat.respond(prepared) { events += it }
            log.single()
        }

        val task = tasks.findAll().single()
        val (_, second) = sent
        val toolResult = toolResults(second).single()
        assertAll(
            { assertEquals("OO기업 자소서 초안", task.instruction) },
            { assertEquals("백엔드 신입, 성장 경험 강조", task.context) },
            { assertTrue(events.contains(ChatEvent.TaskCreated(task.id, "OO기업 자소서 초안")), events.toString()) },
            // 두 번째 요청: 받은 응답(tool_use 포함)을 assistant로, tool_result를 user로 붙인다
            { assertTrue(second.messages()[second.messages().size - 2].content().asBlockParams().any { it.isToolUse() && it.asToolUse().id() == toolUseId }) },
            { assertEquals(toolUseId, toolResult.toolUseId()) },
            { assertEquals(false, toolResult.isError().get()) },
            { assertTrue(toolResult.content().get().asString().contains(task.id.toString())) },
            { assertEquals("작성자에게 맡길게요. 맡겼어요. 완성되면 문서 목록에서 볼 수 있어요.", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.COMPLETE, lastAssistant(prepared.conversationId).status) },
            { assertTrue(line.contains("tool_calls=1"), line) },
        )
    }

    @Test
    fun `입력이 잘못되면 작업을 만들지 않고 오류 결과를 돌려준다`() {
        val prepared = chat.prepare(null, "문서 맡겨줘")
        val sent = messageService.streamsInOrder(
            chatStream { start(); toolUse("create_task", mapOf("instruction" to "  ")); end("tool_use") },
            chatStream { start(); text("무엇을 쓸지 알려 주세요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        val result = toolResults(sent[1]).single()
        assertAll(
            { assertEquals(0, tasks.count()) },
            { assertEquals(true, result.isError().get()) },
            { assertTrue(result.content().get().asString().contains("instruction")) },
            { assertFalse(events.any { it is ChatEvent.TaskCreated }) },
            { assertEquals("무엇을 쓸지 알려 주세요.", lastAssistant(prepared.conversationId).content) },
        )
    }

    @Test
    fun `한 답변에서 두 번 불러도 작업은 하나만 만든다`() {
        val prepared = chat.prepare(null, "문서 두 개 맡겨줘")
        val sent = messageService.streamsInOrder(
            chatStream {
                start()
                toolUse("create_task", mapOf("instruction" to "문서 1"))
                toolUse("create_task", mapOf("instruction" to "문서 2"))
                end("tool_use")
            },
            chatStream { start(); text("하나만 맡겼어요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        val results = toolResults(sent[1])
        assertAll(
            { assertEquals(listOf("문서 1"), tasks.findAll().map { it.instruction }) },
            { assertEquals(listOf(false, true), results.map { it.isError().get() }) },
            { assertEquals(1, events.count { it is ChatEvent.TaskCreated }) },
        )
    }

    @Test
    fun `출력 한도로 끝난 응답의 도구 호출은 실행하지 않는다`() {
        val prepared = chat.prepare(null, "문서 맡겨줘")
        val sent = messageService.streamsInOrder(
            chatStream { start(); text("맡길게요."); toolUse("create_task", mapOf("instruction" to "잘린 지시")); end("max_tokens") },
        )

        chat.respond(prepared) { events += it }

        assertAll(
            { assertEquals(1, sent.size) },
            { assertEquals(0, tasks.count()) },
            { assertEquals(MessageStatus.TRUNCATED, lastAssistant(prepared.conversationId).status) },
        )
    }

    @Test
    fun `웹 검색과 함께 쓰면 출처와 작업이 모두 남는다`() {
        val prepared = chat.prepare(null, "OO기업 조사해서 자소서 맡겨줘")
        messageService.streamsInOrder(
            chatStream {
                start()
                webSearch("OO기업")
                text("OO기업은 물류 회사예요.", citation = "https://oo.example.com" to "OO기업 소개")
                toolUse("create_task", mapOf("instruction" to "OO기업 자소서", "context" to "물류 회사"))
                end("tool_use", searches = 1)
            },
            chatStream { start(); text(" 자소서를 맡겼어요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        val saved = lastAssistant(prepared.conversationId).content
        assertAll(
            { assertEquals(1, tasks.count()) },
            { assertTrue(saved.startsWith("OO기업은 물류 회사예요. 자소서를 맡겼어요."), saved) },
            { assertTrue(saved.endsWith("- [OO기업 소개](https://oo.example.com)"), saved) },
        )
    }

    @Test
    fun `도구 이어 받기 한도를 넘기면 TRUNCATED로 저장한다`() {
        val prepared = chat.prepare(null, "계속 맡겨줘")
        val round = { n: Int -> chatStream { start(); text("$n."); toolUse("create_task", mapOf("instruction" to "")); end("tool_use") } }
        val sent = messageService.streamsInOrder(round(1), round(2), round(3), round(4), round(5))

        chat.respond(prepared) { events += it }

        assertAll(
            // 첫 요청 + 도구 결과를 돌려주며 이어 받기 4번
            { assertEquals(5, sent.size) },
            { assertEquals("1.2.3.4.5.", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.TRUNCATED, lastAssistant(prepared.conversationId).status) },
        )
    }

    private fun toolResults(params: MessageCreateParams): List<ToolResultBlockParam> {
        val last = params.messages().last()
        assertEquals(MessageParam.Role.USER, last.role())
        return last.content().asBlockParams().filter { it.isToolResult() }.map { it.asToolResult() }
    }

    private fun lastAssistant(conversationId: UUID): ChatMessage =
        messages.findByConversationIdOrderByIdAsc(conversationId).last { it.role == "assistant" }
}
