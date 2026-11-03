package com.samoyed.ai_manager_server.auth

import com.anthropic.client.AnthropicClient
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

/** 로그인(비밀번호 + 인증 앱 코드), 세션, 시도 제한 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class AuthApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var auth: AuthService
    @Autowired lateinit var jdbc: JdbcClient

    private val secret = Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ")

    @BeforeEach
    fun setUp() {
        // 다른 테스트 클래스가 쓰는 세션(TestAuth)은 남기고 시도 기록과 쓴 코드만 지운다
        jdbc.sql("delete from login_attempts").update()
        jdbc.sql("delete from totp_used_codes").update()
    }

    @Test
    fun `비밀번호와 인증 앱 코드가 맞으면 토큰을 주고 그 토큰으로 API를 쓴다`() {
        val token = token(login(PASSWORD, currentCode(), "아이폰").andExpect {
            status { isOk() }
            jsonPath("$.expiresAt") { exists() }
        })

        mvc.get("/conversations") { header("Authorization", "Bearer $token") }.andExpect { status { isOk() } }
        mvc.get("/auth/sessions") { header("Authorization", "Bearer $token") }.andExpect {
            jsonPath("$[0].deviceName") { value("아이폰") }
            jsonPath("$[0].current") { value(true) }
        }
    }

    @Test
    fun `비밀번호나 코드가 틀리면 같은 401을 준다`() {
        login("wrong-password-1234", currentCode()).andExpect {
            status { isUnauthorized() }
            content { json("""{"error":"invalid_credentials"}""") }
        }
        login(PASSWORD, "000000").andExpect {
            status { isUnauthorized() }
            content { json("""{"error":"invalid_credentials"}""") }
        }
    }

    @Test
    fun `같은 코드는 한 번만 쓸 수 있다`() {
        val code = currentCode()
        login(PASSWORD, code).andExpect { status { isOk() } }
        login(PASSWORD, code).andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `같은 IP에서 5번 실패하면 맞는 값으로도 15분 동안 막힌다`() {
        repeat(MAX_FAILURES_PER_IP) { login(PASSWORD, "000000").andExpect { status { isUnauthorized() } } }

        login(PASSWORD, currentCode()).andExpect {
            status { isTooManyRequests() }
            header { exists("Retry-After") }
            jsonPath("$.error") { value("too_many_attempts") }
        }

        // 실패 기록이 15분 창 밖으로 나가면 풀린다
        jdbc.sql("update login_attempts set created_at = created_at - interval '16 minutes'").update()
        login(PASSWORD, currentCode()).andExpect { status { isOk() } }
    }

    @Test
    fun `로그아웃한 토큰과 끊은 기기의 토큰은 401`() {
        val phone = auth.issueSession("폰").token
        val laptop = auth.issueSession("노트북").token

        mvc.post("/auth/logout") { header("Authorization", "Bearer $phone") }.andExpect { status { isNoContent() } }
        mvc.get("/conversations") { header("Authorization", "Bearer $phone") }.andExpect { status { isUnauthorized() } }

        val laptopId = sessionId("노트북")
        val other = auth.issueSession("태블릿").token
        mvc.delete("/auth/sessions/$laptopId") { header("Authorization", "Bearer $other") }.andExpect { status { isNoContent() } }
        mvc.get("/conversations") { header("Authorization", "Bearer $laptop") }.andExpect { status { isUnauthorized() } }
        mvc.delete("/auth/sessions/$laptopId") { header("Authorization", "Bearer $other") }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `지금 기기를 뺀 모든 기기를 끊는다`() {
        val keep = auth.issueSession("지금 기기").token
        val gone = auth.issueSession("다른 기기").token

        mvc.delete("/auth/sessions") { header("Authorization", "Bearer $keep") }.andExpect { status { isOk() } }

        mvc.get("/conversations") { header("Authorization", "Bearer $keep") }.andExpect { status { isOk() } }
        mvc.get("/conversations") { header("Authorization", "Bearer $gone") }.andExpect { status { isUnauthorized() } }
        // 같은 컨텍스트의 다른 테스트 클래스가 쓰는 공용 세션(TestAuth)까지 끊겼으니 되살린다
        jdbc.sql("update auth_sessions set revoked_at = null where device_name = 'test'").update()
    }

    @Test
    fun `30일 동안 안 쓰거나 최대 기간이 지난 세션은 만료된다`() {
        val idle = auth.issueSession("오래 안 쓴 기기")
        val old = auth.issueSession("오래된 기기")
        jdbc.sql("update auth_sessions set last_used_at = now() - interval '31 days' where id = :id").param("id", idle.sessionId).update()
        jdbc.sql("update auth_sessions set expires_at = now() - interval '1 second' where id = :id").param("id", old.sessionId).update()

        mvc.get("/conversations") { header("Authorization", "Bearer ${idle.token}") }.andExpect { status { isUnauthorized() } }
        mvc.get("/conversations") { header("Authorization", "Bearer ${old.token}") }.andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `토큰은 해시로만 저장하고, 예전 패스코드로는 세션 관리를 못 한다`() {
        val issued = auth.issueSession("해시 확인")
        val stored = jdbc.sql("select token_hash from auth_sessions where id = :id").param("id", issued.sessionId).query(String::class.java).single()
        assertEquals(64, stored.length)
        assertEquals(false, stored.contains(issued.token))

        mvc.get("/auth/sessions") { header("X-App-Passcode", "test-passcode") }.andExpect { status { isOk() } }
        mvc.delete("/auth/sessions") { header("X-App-Passcode", "test-passcode") }.andExpect { status { isForbidden() } }
    }

    private fun currentCode(): String = Totp.code(secret, Totp.counterAt(System.currentTimeMillis() / 1000))

    private fun login(password: String, code: String, device: String = "테스트"): ResultActionsDsl = mvc.post("/auth/login") {
        contentType = MediaType.APPLICATION_JSON
        content = """{"password":"$password","totp":"$code","deviceName":"$device"}"""
    }

    private fun token(result: ResultActionsDsl): String =
        Regex(""""token":"([^"]+)"""").find(result.andReturn().response.getContentAsString(Charsets.UTF_8))!!.groupValues[1]

    private fun sessionId(device: String): UUID =
        jdbc.sql("select id from auth_sessions where device_name = :d and revoked_at is null").param("d", device).query(UUID::class.java).single()

    private companion object {
        const val PASSWORD = "test-password-1234"
    }
}
