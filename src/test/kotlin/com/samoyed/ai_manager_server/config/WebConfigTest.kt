package com.samoyed.ai_manager_server.config

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestAuth
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

/** CORS·보안 헤더·인증 필터. MockMvc 테스트끼리(ChatControllerTest) 컨텍스트를 공유한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class WebConfigTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var testAuth: TestAuth

    @Test
    fun `인증 헤더가 없으면 401`() {
        mvc.get("/conversations").andExpect {
            status { isUnauthorized() }
            content { json("""{"error":"unauthorized"}""") }
        }
    }

    @Test
    fun `Bearer 토큰이 맞으면 통과`() {
        mvc.get("/conversations") { header("Authorization", testAuth.bearer) }
            .andExpect { status { isOk() } }
    }

    @Test
    fun `허용된 origin의 preflight는 인증 없이 통과한다`() {
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
    fun `Bearer 토큰이 틀리면 401`() {
        mvc.get("/conversations") { header("Authorization", "Bearer wrong-token") }
            .andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `응답에 보안 헤더가 붙는다`() {
        mvc.get("/conversations") { header("Authorization", testAuth.bearer) }.andExpect {
            header { string("Cache-Control", "no-store") }
            header { string("X-Content-Type-Options", "nosniff") }
            header { string("Referrer-Policy", "no-referrer") }
            // HTTPS가 아니면 HSTS는 붙이지 않는다
            header { doesNotExist("Strict-Transport-Security") }
        }
        mvc.get("/conversations") {
            header("Authorization", testAuth.bearer)
            secure = true
        }.andExpect { header { string("Strict-Transport-Security", "max-age=31536000") } }
    }
}

/** 예전 설정(APP_PASSCODE)이 남아 있어도 X-App-Passcode 헤더로는 들어올 수 없다 */
@SpringBootTest(properties = ["app.passcode=test-passcode"])
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class LegacyPasscodeRemovedTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc

    @Test
    fun `APP_PASSCODE가 설정돼 있어도 X-App-Passcode 헤더는 401`() {
        mvc.get("/conversations") { header("X-App-Passcode", "test-passcode") }
            .andExpect { status { isUnauthorized() } }
    }
}
