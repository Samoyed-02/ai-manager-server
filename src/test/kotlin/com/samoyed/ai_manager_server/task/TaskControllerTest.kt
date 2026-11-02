package com.samoyed.ai_manager_server.task

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.profile.ContextService
import jakarta.persistence.EntityManagerFactory
import org.hibernate.SessionFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class TaskControllerTest {

    /** 다른 테스트와 같은 설정이라 Spring 컨텍스트를 공유한다 */
    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var controller: TaskController
    @Autowired lateinit var context: ContextService
    @Autowired lateinit var jdbc: JdbcClient
    @Autowired lateinit var emf: EntityManagerFactory

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from documents").update()
        jdbc.sql("delete from tasks").update()
    }

    @Test
    fun `작업 목록은 문서 ID를 한 번에 조회한다`() {
        val done = (1..5).map { insertTask("DONE", minutesAgo = it) }
        val pending = (6..7).map { insertTask("PENDING", minutesAgo = it) }
        val docIds = done.associateWith { insertDocument(it, "문서", minutesAgo = 0) }

        val stats = emf.unwrap(SessionFactory::class.java).statistics
        stats.clear()
        val result = controller.list()
        val statements = stats.prepareStatementCount

        assertAll(
            { assertEquals(2, statements, "작업 목록 1번 + 문서 ID 1번") },
            { assertEquals(7, result.size) },
            { done.forEach { id -> assertEquals(docIds[id], result.single { it.id == id }.documentId) } },
            { pending.forEach { id -> assertNull(result.single { it.id == id }.documentId) } },
        )
    }

    @Test
    fun `문서 목록은 최신순으로 최대 50개를 돌려준다`() {
        val task = insertTask("DONE", minutesAgo = 0)
        insertDocument(task, "작업 문서", minutesAgo = 60)
        (1..55).forEach { insertDocument(null, "문서 $it", minutesAgo = it) }

        val result = controller.documents()

        assertAll(
            { assertEquals(50, result.size) },
            { assertEquals("문서 1", result.first().title) },
            { assertEquals(result.sortedByDescending { it.createdAt }, result) },
            { assertTrue(result.none { it.title == "작업 문서" }, "51번째 이후는 빠져야 함") },
        )
    }

    @Test
    fun `컨텍스트에는 최근 문서 제목 10개가 최신순으로 들어간다`() {
        (1..12).forEach { insertDocument(null, "문서 $it", minutesAgo = it) }

        val text = context.forClaude()

        val titles = text.lineSequence().filter { it.startsWith("- 문서 ") }.map { it.removePrefix("- ") }.toList()
        assertEquals((1..10).map { "문서 $it" }, titles)
    }

    private fun insertTask(status: String, minutesAgo: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into tasks (id, type, instruction, status, created_at)
            values (:id, 'document', '작업', :status, now() - :m * interval '1 minute')
            """.trimIndent()
        ).param("id", id).param("status", status).param("m", minutesAgo).update()
        return id
    }

    private fun insertDocument(taskId: UUID?, title: String, minutesAgo: Int): UUID {
        val id = UUID.randomUUID()
        jdbc.sql(
            """
            insert into documents (id, task_id, title, content, created_at)
            values (:id, :taskId, :title, '본문', now() - :m * interval '1 minute')
            """.trimIndent()
        ).param("id", id).param("taskId", taskId).param("title", title).param("m", minutesAgo).update()
        return id
    }
}
