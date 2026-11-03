package com.samoyed.ai_manager_server.memory

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestAuth
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** 기억 조회·추가·수정·삭제. 컨텍스트 설정이 다른 MockMvc 테스트와 같아 공유된다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class MemoryApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var testAuth: TestAuth
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from memories").update()
    }

    @Test
    fun `기억을 추가하고 카테고리별로 조회한다`() {
        post("""{"category":"GOAL","content":"정보처리기사 합격"}""").andExpect {
            status { isCreated() }
            jsonPath("$.category") { value("GOAL") }
            jsonPath("$.sourceConversationId") { value(null) }
        }
        post("""{"category":"ROUTINE","content":"매일 아침 7시 기상"}""").andExpect { status { isCreated() } }

        mvc.get("/memories") { header("Authorization", testAuth.bearer) }.andExpect { jsonPath("$.length()") { value(2) } }
        mvc.get("/memories?category=GOAL") { header("Authorization", testAuth.bearer) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].content") { value("정보처리기사 합격") }
        }
    }

    @Test
    fun `기억을 고치고 지운다`() {
        val memory = memories.save(Memory(MemoryCategory.GOAL, "토익 800점"))

        mvc.put("/memories/${memory.id}") {
            header("Authorization", testAuth.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"PROGRESS","content":"토익 850점 달성"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.content") { value("토익 850점 달성") }
        }
        assertEquals(MemoryCategory.PROGRESS, memories.findById(memory.id).get().category)

        mvc.delete("/memories/${memory.id}") { header("Authorization", testAuth.bearer) }.andExpect { status { isNoContent() } }
        mvc.delete("/memories/${memory.id}") { header("Authorization", testAuth.bearer) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `민감 정보는 기억으로 저장하지 않는다`() {
        post("""{"category":"FACT","content":"비밀번호: abc123!"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("invalid_request") }
            jsonPath("$.fields.content") { exists() }
        }
        assertEquals(0, memories.count())
    }

    @Test
    fun `카테고리가 잘못되거나 내용이 비면 400`() {
        post("""{"category":"SECRET","content":"내용"}""").andExpect { status { isBadRequest() } }
        post("""{"category":"FACT","content":" "}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.content") { exists() }
        }
    }

    @Test
    fun `패스코드가 없으면 401`() {
        mvc.get("/memories").andExpect { status { isUnauthorized() } }
    }

    private fun post(body: String) = mvc.post("/memories") {
        header("Authorization", testAuth.bearer)
        contentType = MediaType.APPLICATION_JSON
        content = body
    }

}
