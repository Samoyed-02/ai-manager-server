package com.samoyed.ai_manager_server.task

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.TextBlock
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TaskWorkerConcurrencyTest {

    @MockitoBean
    lateinit var client: AnthropicClient

    @Autowired lateinit var tasks: TaskRepository
    @Autowired lateinit var worker: TaskWorker
    @Autowired lateinit var controller: TaskController
    @Autowired lateinit var jdbc: JdbcClient

    private val messages: MessageService = mock(MessageService::class.java)
    private val reply: Message = response()

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from documents").update()
        jdbc.sql("delete from tasks").update()
        `when`(client.messages()).thenReturn(messages)
        `when`(messages.create(anyArg(MessageCreateParams::class.java))).thenReturn(reply)
    }

    @Test
    fun `동시에 poll해도 작업마다 한 번씩만 처리된다`() {
        val ids = (1..20).map { tasks.save(Task("document", "작업 $it")).id }
        `when`(messages.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            Thread.sleep(20)
            reply
        }

        val threads = 8
        val start = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.submit {
                start.await()
                while (countByStatus("PENDING") > 0) worker.poll()
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS), "워커가 시간 안에 끝나지 않음")

        assertAll(
            { assertEquals(ids.size, countByStatus("DONE")) },
            { assertEquals(ids.size, jdbc.sql("select count(*) from tasks where attempts = 1").query(Int::class.java).single()) },
            { ids.forEach { assertEquals(1, documentCount(it), "task $it 문서 수") } },
        )
    }

    @Test
    fun `오래 걸리는 작업이 stale로 복구돼도 문서는 하나만 생긴다`() {
        val id = tasks.save(Task("document", "느린 작업")).id
        val firstCallStarted = CountDownLatch(1)
        val releaseFirstCall = CountDownLatch(1)
        val calls = AtomicInteger()
        `when`(messages.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            if (calls.incrementAndGet() == 1) {
                firstCallStarted.countDown()
                releaseFirstCall.await(10, TimeUnit.SECONDS)
            }
            reply
        }

        // 워커 A: Claude 응답을 기다리는 중
        val workerA = thread { worker.poll() }
        assertTrue(firstCallStarted.await(10, TimeUnit.SECONDS))

        // 10분이 지난 것처럼 만들고 복구 → 워커 B가 같은 작업을 다시 가져감
        jdbc.sql("update tasks set started_at = now() - interval '11 minutes' where id = :id")
            .param("id", id).update()
        worker.recoverStale()
        worker.poll()

        releaseFirstCall.countDown()
        workerA.join(10_000)

        assertAll(
            { assertEquals(1, documentCount(id), "task 문서 수") },
            { assertDoesNotThrow { controller.get(id) } },
        )
    }

    @Test
    fun `문서 저장 후 작업 상태 저장이 실패해도 재시도 시 문서가 중복되지 않는다`() {
        val id = tasks.save(Task("document", "저장 실패 작업")).id

        // 문서 저장 직후 상태 저장에서 죽는다 (서버 크래시 흉내): DONE으로 바꾸는 update만 DB에서 막는다
        jdbc.sql(
            """
            create or replace function fail_task_done() returns trigger as $$
            begin raise exception 'crash after document save'; end
            $$ language plpgsql
            """.trimIndent()
        ).update()
        jdbc.sql(
            """
            create trigger fail_task_done before update on tasks
               for each row when (new.status = 'DONE') execute function fail_task_done()
            """.trimIndent()
        ).update()
        try {
            runCatching { worker.poll() }
        } finally {
            jdbc.sql("drop trigger fail_task_done on tasks").update()
            jdbc.sql("drop function fail_task_done()").update()
        }
        assertEquals("RUNNING", status(id))
        assertEquals(1, documentCount(id), "크래시 시점에 이미 저장된 문서 수")

        jdbc.sql("update tasks set started_at = now() - interval '11 minutes' where id = :id")
            .param("id", id).update()
        worker.recoverStale()
        worker.poll()

        assertAll(
            { assertEquals("DONE", status(id)) },
            { assertEquals(1, documentCount(id), "task 문서 수") },
            { assertDoesNotThrow { controller.get(id) } },
        )
    }

    private fun response(text: String = "# 테스트 문서\n본문"): Message {
        val textBlock = mock(TextBlock::class.java)
        `when`(textBlock.text()).thenReturn(text)
        val block = mock(ContentBlock::class.java)
        `when`(block.text()).thenReturn(java.util.Optional.of(textBlock))
        val message = mock(Message::class.java)
        `when`(message.content()).thenReturn(listOf(block))
        return message
    }

    /** Kotlin으로 작성된 SDK는 null 인자를 거부하므로 matcher만 등록하고 null을 그대로 넘긴다 */
    @Suppress("UNCHECKED_CAST")
    private fun <T> anyArg(type: Class<T>): T {
        any(type)
        return null as T
    }

    private fun countByStatus(status: String): Int =
        jdbc.sql("select count(*) from tasks where status = :s").param("s", status)
            .query(Int::class.java).single()

    private fun status(id: UUID): String =
        jdbc.sql("select status from tasks where id = :id").param("id", id)
            .query(String::class.java).single()

    private fun documentCount(taskId: UUID): Int =
        jdbc.sql("select count(*) from documents where task_id = :id").param("id", taskId)
            .query(Int::class.java).single()
}
