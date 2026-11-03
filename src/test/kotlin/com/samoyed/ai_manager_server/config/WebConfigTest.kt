package com.samoyed.ai_manager_server.config

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.options
import kotlin.test.assertFailsWith

/** CORS·패스코드 필터까지 포함한 요청 흐름. MockMvc 테스트끼리(ChatControllerTest) 컨텍스트를 공유한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WebConfigTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `패스코드 헤더가 없으면 401`() {
        mvc.get("/conversations").andExpect {
            status { isUnauthorized() }
            content { json("""{"error":"unauthorized"}""") }
        }
    }

    @Test
    fun `패스코드가 틀리면 401`() {
        mvc.get("/conversations") { header("X-App-Passcode", "wrong-passcode") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `패스코드가 앞부분만 같아도 401`() {
        mvc.get("/conversations") { header("X-App-Passcode", "test-passcode-extra") }
            .andExpect { status { isUnauthorized() } }
        mvc.get("/conversations") { header("X-App-Passcode", "test-pass") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `패스코드가 맞으면 통과`() {
        mvc.get("/conversations") { header("X-App-Passcode", "test-passcode") }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `허용된 origin의 preflight는 패스코드 없이 통과한다`() {
        mvc.options("/conversations") {
            header("Origin", "http://localhost:3000")
            header("Access-Control-Request-Method", "GET")
        }.andExpect {
            status { isOk() }
            header { string("Access-Control-Allow-Origin", "http://localhost:3000") }
        }
    }

    @Test
    fun `허용되지 않은 origin의 preflight는 403`() {
        mvc.options("/conversations") {
            header("Origin", "http://evil.example")
            header("Access-Control-Request-Method", "GET")
        }.andExpect { status { isForbidden() } }
    }

    @Test
    fun `패스코드가 비어 있으면 기동하지 않는다`() {
        for (blank in listOf("", "   ")) {
            assertFailsWith<IllegalArgumentException> { WebConfig("http://localhost:3000", blank) }
        }
    }
}
