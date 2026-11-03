package com.samoyed.ai_manager_server.config

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestAuth
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.chat.MAX_CHAT_MESSAGE
import com.samoyed.ai_manager_server.profile.MAX_PROFILE_FULL
import com.samoyed.ai_manager_server.task.MAX_TASK_CONTEXT
import com.samoyed.ai_manager_server.task.MAX_TASK_INSTRUCTION
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** 요청 검증과 400 응답 형식. WebConfigTest와 같은 설정이라 컨텍스트를 공유한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ValidationTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var testAuth: TestAuth
    @Autowired lateinit var jdbc: JdbcClient

    @Test
    fun `채팅 메시지가 한도를 넘으면 400과 필드별 사유를 돌려준다`() {
        send("/chat", """{"message":"${"a".repeat(MAX_CHAT_MESSAGE + 1)}"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.error") { value("invalid_request") }
            jsonPath("$.fields.message") { value("최대 ${MAX_CHAT_MESSAGE}자까지 입력할 수 있습니다") }
        }
    }

    @Test
    fun `채팅 메시지가 비어 있으면 400`() {
        send("/chat", """{"message":" "}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.message") { exists() }
        }
    }

    @Test
    fun `지원하지 않는 작업 종류는 400`() {
        send("/tasks", """{"instruction":"작성해줘","type":"essay"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.type") { value("지원하지 않는 작업 종류입니다") }
        }
    }

    @Test
    fun `작업 지시가 정확히 한도 길이면 통과한다`() {
        send("/tasks", """{"instruction":"${"a".repeat(MAX_TASK_INSTRUCTION)}"}""")
            .andExpect { status { isAccepted() } }
    }

    @Test
    fun `작업 참고 맥락이 한도를 넘으면 400`() {
        send("/tasks", """{"instruction":"작성해줘","context":"${"a".repeat(MAX_TASK_CONTEXT + 1)}"}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.context") { exists() }
        }
    }

    @Test
    fun `프로필이 한도를 넘으면 400이고 기존 프로필은 바뀌지 않는다`() {
        val before = fullProfile()

        mvc.put("/profile") {
            header("Authorization", testAuth.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"fullMd":"${"a".repeat(MAX_PROFILE_FULL + 1)}","briefMd":"요약"}"""
        }.andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.fullMd") { exists() }
        }

        assertEquals(before, fullProfile())
    }

    @Test
    fun `JSON 형식이 잘못되면 400과 원문 없는 응답`() {
        send("/tasks", """{"instruction": """).andExpect {
            status { isBadRequest() }
            content { json("""{"error":"invalid_request","fields":{}}""", JsonCompareMode.STRICT) }
        }
    }

    private fun send(path: String, body: String): ResultActionsDsl =
        mvc.post(path) {
            header("Authorization", testAuth.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = body
        }

    private fun fullProfile(): String =
        jdbc.sql("select full_md from profile where id = 1").query(String::class.java).single()
}
