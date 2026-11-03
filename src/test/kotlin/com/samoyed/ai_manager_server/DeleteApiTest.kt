package com.samoyed.ai_manager_server

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.chat.ChatMessage
import com.samoyed.ai_manager_server.chat.ChatMessageRepository
import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationRepository
import com.samoyed.ai_manager_server.task.Document
import com.samoyed.ai_manager_server.task.DocumentRepository
import com.samoyed.ai_manager_server.task.Task
import com.samoyed.ai_manager_server.task.TaskRepository
import com.samoyed.ai_manager_server.task.TaskStatus
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get

/** DELETE API. 컨텍스트 설정이 WebConfigTest·ChatControllerTest와 같아 공유된다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class DeleteApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var conversations: ConversationRepository
    @Autowired lateinit var messages: ChatMessageRepository
    @Autowired lateinit var tasks: TaskRepository
    @Autowired lateinit var documents: DocumentRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
        jdbc.sql("delete from documents").update()
        jdbc.sql("delete from tasks").update()
    }

    @Test
    fun `대화를 지우면 메시지도 함께 지워지고 다시 지우면 404`() {
        val conversation = conversations.save(Conversation(title = "대화"))
        messages.save(ChatMessage(conversation.id, "user", "안녕"))
        messages.save(ChatMessage(conversation.id, "assistant", "안녕하세요"))

        delete("/conversations/${conversation.id}").andExpect { status { isNoContent() } }

        assertAll(
            { assertFalse(conversations.existsById(conversation.id)) },
            { assertEquals(0, messages.findByConversationIdOrderByIdAsc(conversation.id).size) },
        )
        delete("/conversations/${conversation.id}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `작업을 지워도 문서는 남고 task_id만 비워진다`() {
        val task = doneTask()
        val document = documents.save(Document(task.id, "문서", "본문"))

        delete("/tasks/${task.id}").andExpect { status { isNoContent() } }

        mvc.get("/tasks/${task.id}") { header(PASSCODE_HEADER, PASSCODE) }
            .andExpect { status { isNotFound() } }
        mvc.get("/documents/${document.id}") { header(PASSCODE_HEADER, PASSCODE) }.andExpect {
            status { isOk() }
            jsonPath("$.title") { value("문서") }
            jsonPath("$.taskId") { value(null) }
        }
        delete("/tasks/${task.id}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `문서를 지우면 작업은 DONE으로 남고 documentId만 null이 된다`() {
        val task = doneTask()
        val document = documents.save(Document(task.id, "문서", "본문"))

        delete("/documents/${document.id}").andExpect { status { isNoContent() } }

        mvc.get("/tasks/${task.id}") { header(PASSCODE_HEADER, PASSCODE) }.andExpect {
            status { isOk() }
            jsonPath("$.status") { value("DONE") }
            jsonPath("$.documentId") { value(null) }
        }
        assertNull(documents.findRefByTaskId(task.id))
        delete("/documents/${document.id}").andExpect { status { isNotFound() } }
    }

    @Test
    fun `패스코드가 없으면 지우지 않는다`() {
        val conversation = conversations.save(Conversation(title = "대화"))

        mvc.delete("/conversations/${conversation.id}").andExpect { status { isUnauthorized() } }

        assertEquals(true, conversations.existsById(conversation.id))
    }

    private fun doneTask(): Task =
        tasks.save(Task("document", "문서 작성").apply { status = TaskStatus.DONE })

    private fun delete(path: String) = mvc.delete(path) { header(PASSCODE_HEADER, PASSCODE) }

    private companion object {
        const val PASSCODE_HEADER = "X-App-Passcode"
        const val PASSCODE = "test-passcode"
    }
}
