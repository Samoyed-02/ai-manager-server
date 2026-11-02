package com.samoyed.ai_manager_server.task

import com.anthropic.client.AnthropicClient
import com.anthropic.core.http.Headers
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.claude.API_ERROR_BODY
import com.samoyed.ai_manager_server.claude.ClaudeErrorKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TaskWorkerStopReasonTest {

    /** RETURNS_SELF: withOptions(...)가 mock 자신을 돌려준다 */
    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var tasks: TaskRepository
    @Autowired lateinit var worker: TaskWorker
    @Autowired lateinit var jdbc: JdbcClient

    private val messages: MessageService = mock(MessageService::class.java)

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from documents").update()
        jdbc.sql("delete from tasks").update()
        `when`(client.messages()).thenReturn(messages)
    }

    @Test
    fun `출력 한도에서 잘린 응답은 재시도 없이 실패 처리된다`() {
        val id = runOnce(claudeResponse(text = "# 잘린 문서\n본문이 중간에", stopReason = StopReason.MAX_TOKENS, outputTokens = 8192))

        val row = row(id)
        assertAll(
            { assertEquals("FAILED", row.status) },
            { assertEquals(1, row.attempts) },
            { assertNotNull(row.finishedAt) },
            { assertTrue(row.error!!.contains("출력 한도"), row.error) },
            { assertEquals(0, documentCount(id)) },
        )
    }

    @Test
    fun `거절된 응답은 재시도 없이 실패 처리되고 category가 기록된다`() {
        val id = runOnce(claudeResponse(text = "", stopReason = StopReason.REFUSAL, refusalCategory = "cyber"))

        val row = row(id)
        assertAll(
            { assertEquals("FAILED", row.status) },
            { assertEquals(1, row.attempts) },
            { assertTrue(row.error!!.contains("거절") && row.error.contains("cyber"), row.error) },
            { assertEquals(0, documentCount(id)) },
        )
    }

    @Test
    fun `정상 종료인데 본문이 비어 있으면 재시도 대기로 돌아간다`() {
        val id = runOnce(claudeResponse(text = "  "))

        val row = row(id)
        assertAll(
            { assertEquals("PENDING", row.status) },
            { assertEquals(1, row.attempts) },
            { assertNull(row.finishedAt) },
            { assertTrue(row.error!!.contains("본문이 없음"), row.error) },
            { assertEquals(0, documentCount(id)) },
        )
    }

    @Test
    fun `다시 해도 같은 결과인 API 오류는 재시도 없이 실패하고 원문은 저장하지 않는다`() {
        val id = tasks.save(Task("document", "문서 작성")).id
        `when`(messages.create(anyArg(MessageCreateParams::class.java)))
            .thenThrow(UnauthorizedException.builder().headers(Headers.builder().build()).body(API_ERROR_BODY).build())
        worker.poll()

        val row = row(id)
        assertAll(
            { assertEquals("FAILED", row.status) },
            { assertEquals(1, row.attempts) },
            { assertEquals(ClaudeErrorKind.CONFIG.userMessage, row.error) },
        )
    }

    @Test
    fun `일시적인 API 오류는 재시도 대기로 돌아가고 원문은 저장하지 않는다`() {
        val id = tasks.save(Task("document", "문서 작성")).id
        `when`(messages.create(anyArg(MessageCreateParams::class.java)))
            .thenThrow(RateLimitException.builder().headers(Headers.builder().build()).body(API_ERROR_BODY).build())
        worker.poll()

        val row = row(id)
        assertAll(
            { assertEquals("PENDING", row.status) },
            { assertEquals(ClaudeErrorKind.RATE_LIMITED.userMessage, row.error) },
        )
    }

    private fun runOnce(reply: Message): UUID {
        val id = tasks.save(Task("document", "문서 작성")).id
        `when`(messages.create(anyArg(MessageCreateParams::class.java))).thenReturn(reply)
        worker.poll()
        return id
    }

    private data class TaskRow(val status: String, val attempts: Int, val error: String?, val finishedAt: Any?)

    private fun row(id: UUID): TaskRow =
        jdbc.sql("select status, attempts, error, finished_at from tasks where id = :id").param("id", id)
            .query { rs, _ -> TaskRow(rs.getString("status"), rs.getInt("attempts"), rs.getString("error"), rs.getObject("finished_at")) }
            .single()

    private fun documentCount(taskId: UUID): Int =
        jdbc.sql("select count(*) from documents where task_id = :id").param("id", taskId)
            .query(Int::class.java).single()
}
