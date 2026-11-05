package com.samoyed.ai_manager_server

import com.anthropic.client.AnthropicClient
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.json.JsonCompareMode
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

/** 배포 플랫폼의 헬스체크. 컨텍스트 설정이 DeleteApiTest와 같아 공유된다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class HealthCheckTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var testAuth: TestAuth

    @Test
    fun `헬스체크는 인증 없이 상태만 돌려준다`() {
        mvc.get("/actuator/health").andExpect {
            status { isOk() }
            content { json("""{"status":"UP"}""", JsonCompareMode.STRICT) }
        }
    }

    @Test
    fun `헬스체크 하위 경로와 다른 actuator 경로는 인증 없이 열리지 않는다`() {
        listOf("/actuator/health/db", "/actuator/env", "/actuator/beans", "/actuator").forEach { path ->
            mvc.get(path).andExpect { status { isUnauthorized() } }
        }
    }

    @Test
    fun `로그인해도 헬스체크 말고 다른 actuator 경로는 없다`() {
        listOf("/actuator/env", "/actuator/beans", "/actuator/configprops").forEach { path ->
            mvc.get(path) { header("Authorization", testAuth.bearer) }.andExpect { status { isNotFound() } }
        }
    }
}
