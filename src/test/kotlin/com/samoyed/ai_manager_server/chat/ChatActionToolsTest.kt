package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.ToolResultBlockParam
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.routine.RoutineKind
import com.samoyed.ai_manager_server.routine.RoutineRepository
import com.samoyed.ai_manager_server.routine.RoutineService
import org.junit.jupiter.api.Assertions.assertEquals
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
import java.time.LocalTime

/** 채팅에서 말로 기억·루틴·활동 도구를 쓰는 흐름 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatActionToolsTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var activities: ActivityLogRepository
    @Autowired lateinit var routines: RoutineRepository
    @Autowired lateinit var routineService: RoutineService
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val events = mutableListOf<ChatEvent>()

    @BeforeEach
    fun setUp() {
        listOf("notifications", "routines", "activity_logs", "memories", "conversations").forEach { jdbc.sql("delete from $it").update() }
        `when`(client.messages()).thenReturn(messageService)
        events.clear()
    }

    @Test
    fun `한 답변에서 활동 기록과 기억 저장을 하고 action 이벤트를 보낸다`() {
        val prepared = chat.prepare(null, "오늘 알고리즘 2시간 했어. 앞으로 답은 짧게 해줘")
        messageService.streamsInOrder(
            chatStream {
                start()
                toolUse("log_activity", mapOf("description" to "알고리즘 공부", "category" to "STUDY", "minutes" to 120))
                toolUse("save_memory", mapOf("category" to "PREFERENCE", "content" to "사용자는 짧은 답을 좋아한다"))
                end("tool_use")
            },
            chatStream { start(); text("기록했고, 앞으로 짧게 답할게요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        val log = activities.findAll().single()
        val memory = memories.findAll().single()
        assertAll(
            { assertEquals(120, log.minutes) },
            // 채팅에서 만든 기록은 그 대화를 출처로 남긴다
            { assertEquals(prepared.conversationId, log.sourceConversationId) },
            { assertEquals(prepared.conversationId, memory.sourceConversationId) },
            {
                assertEquals(
                    listOf(
                        ChatEvent.Action("log_activity", "활동 기록: 알고리즘 공부 120분"),
                        ChatEvent.Action("save_memory", "기억함: 사용자는 짧은 답을 좋아한다"),
                    ),
                    events.filterIsInstance<ChatEvent.Action>(),
                )
            },
        )
    }

    @Test
    fun `목록을 본 뒤 다음 요청에서 루틴을 끈다`() {
        val routine = routineService.create("밤 기록", RoutineKind.REMINDER, "공부 기록해", LocalTime.of(22, 0), emptySet())
        val prepared = chat.prepare(null, "밤 기록 루틴 잠깐 꺼줘")
        val sent = messageService.streamsInOrder(
            chatStream { start(); toolUse("list_routines", emptyMap()); end("tool_use") },
            chatStream { start(); toolUse("update_routine", mapOf("id" to routine.id.toString(), "enabled" to false)); end("tool_use") },
            chatStream { start(); text("밤 기록 루틴을 껐어요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        assertAll(
            { assertEquals(3, sent.size) },
            // 목록 결과에 id가 들어가 다음 호출에서 쓸 수 있다
            { assertTrue(toolResults(sent[1]).single().content().get().asString().contains(routine.id.toString())) },
            { assertEquals(false, routines.findById(routine.id).get().enabled) },
            { assertEquals(ChatEvent.Action("update_routine", "루틴 수정: 밤 기록 (꺼짐)"), events.filterIsInstance<ChatEvent.Action>().single()) },
        )
    }

    @Test
    fun `답변당 횟수를 넘긴 호출과 실패한 호출은 오류 결과로 돌려준다`() {
        val prepared = chat.prepare(null, "기억 여섯 개 해줘")
        val sent = messageService.streamsInOrder(
            chatStream {
                start()
                (1..6).forEach { toolUse("save_memory", mapOf("category" to "FACT", "content" to "사용자에 대한 사실 $it")) }
                toolUse("create_routine", mapOf("title" to "알림", "kind" to "REMINDER", "message" to "m", "time" to "9시"))
                end("tool_use")
            },
            chatStream { start(); text("다섯 개만 기억했어요."); end("end_turn") },
        )

        chat.respond(prepared) { events += it }

        val results = toolResults(sent[1])
        assertAll(
            { assertEquals(5, memories.count()) },
            { assertEquals(listOf(false, false, false, false, false, true, true), results.map { it.isError().get() }) },
            { assertTrue(results.last().content().get().asString().contains("HH:mm")) },
            { assertEquals(0, routines.count()) },
        )
    }

    private fun toolResults(params: MessageCreateParams): List<ToolResultBlockParam> =
        params.messages().last().content().asBlockParams().filter { it.isToolResult() }.map { it.asToolResult() }
}
