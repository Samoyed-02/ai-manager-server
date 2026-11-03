package com.samoyed.ai_manager_server.routine

import com.anthropic.client.AnthropicClient
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.claude.API_ERROR_BODY
import com.samoyed.ai_manager_server.memory.Memory
import com.samoyed.ai_manager_server.memory.MemoryCategory
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.task.anyArg
import com.samoyed.ai_manager_server.task.claudeResponse
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
import java.time.OffsetDateTime
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class RoutineRunnerTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var runner: RoutineRunner
    @Autowired lateinit var routines: RoutineRepository
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val sent = mutableListOf<MessageCreateParams>()

    @BeforeEach
    fun setUp() {
        listOf("notifications", "routines", "memories").forEach { jdbc.sql("delete from $it").update() }
        `when`(client.messages()).thenReturn(messageService)
        sent.clear()
    }

    @Test
    fun `시간이 된 알림 루틴은 문구를 알림함에 넣고 다음 실행으로 넘어간다`() {
        val id = routine(RoutineKind.REMINDER, "오늘 공부 기록해", dueMinutesAgo = 1)

        runner.poll()

        val notification = notifications.findAll().single()
        val routine = routines.findById(id).get()
        assertAll(
            { assertEquals("오늘 공부 기록해", notification.body) },
            { assertEquals(id, notification.routineId) },
            { assertTrue(routine.nextRunAt.isAfter(OffsetDateTime.now())) },
            { assertTrue(routine.lastRunAt != null) },
            { assertEquals(0, sent.size) },
        )
        runner.poll()
        assertEquals(1, notifications.count())
    }

    @Test
    fun `시간이 안 됐거나 꺼진 루틴은 실행하지 않는다`() {
        routine(RoutineKind.REMINDER, "아직", dueMinutesAgo = -5)
        val off = routine(RoutineKind.REMINDER, "꺼짐", dueMinutesAgo = 1)
        jdbc.sql("update routines set enabled = false where id = :id").param("id", off).update()

        runner.poll()

        assertEquals(0, notifications.count())
    }

    @Test
    fun `서버가 꺼져 여러 번 밀렸어도 한 번만 실행한다`() {
        routine(RoutineKind.REMINDER, "물 마시기", dueMinutesAgo = 3 * 24 * 60)

        runner.poll()
        runner.poll()

        assertEquals(1, notifications.count())
    }

    @Test
    fun `브리핑 루틴은 기억을 담아 Claude에게 쓰게 한다`() {
        memories.save(Memory(MemoryCategory.GOAL, "사용자는 정보처리기사 합격이 목표다"))
        routine(RoutineKind.BRIEFING, "오늘 할 일을 정리해 줘", dueMinutesAgo = 1)
        replies { claudeResponse(text = "1. 기출 1회분 풀기\n2. 오답 정리") }

        runner.poll()

        val request = sent.single()
        assertAll(
            { assertEquals("1. 기출 1회분 풀기\n2. 오답 정리", notifications.findAll().single().body) },
            { assertTrue(request.system().get().asString().contains("사용자는 정보처리기사 합격이 목표다")) },
            { assertTrue(request.messages().single().content().asString().contains("오늘 할 일을 정리해 줘")) },
        )
    }

    @Test
    fun `일시적인 오류면 알림 없이 두고 바로 다시 시도하지 않는다`() {
        val id = routine(RoutineKind.BRIEFING, "브리핑", dueMinutesAgo = 1)
        val before = routines.findById(id).get().nextRunAt
        replies { throw AnthropicIoException("connect timed out") }

        runner.poll()
        runner.poll()

        assertAll(
            { assertEquals(1, sent.size) },
            { assertEquals(0, notifications.count()) },
            { assertEquals(before.toInstant(), routines.findById(id).get().nextRunAt.toInstant()) },
        )
    }

    @Test
    fun `다시 해도 같은 오류면 실패를 알리고 다음 실행으로 넘어간다`() {
        val id = routine(RoutineKind.BRIEFING, "브리핑", dueMinutesAgo = 1)
        replies { throw UnauthorizedException.builder().headers(Headers.builder().build()).body(API_ERROR_BODY).build() }

        runner.poll()

        assertAll(
            { assertTrue(notifications.findAll().single().body.startsWith("브리핑을 만들지 못했어요.")) },
            { assertTrue(routines.findById(id).get().nextRunAt.isAfter(OffsetDateTime.now())) },
        )
    }

    private fun routine(kind: RoutineKind, message: String, dueMinutesAgo: Long): UUID {
        val routine = routines.save(
            Routine("테스트 루틴", kind, message, "", LocalTime.of(8, 0), OffsetDateTime.now().minusMinutes(dueMinutesAgo))
        )
        return routine.id
    }

    private fun replies(answer: () -> Any) {
        `when`(messageService.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            sent += it.getArgument<MessageCreateParams>(0)
            answer()
        }
    }
}
