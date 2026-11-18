package com.samoyed.ai_manager_server.config

import com.samoyed.ai_manager_server.auth.AuthService
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.mock.web.MockHttpServletRequest

/** 시도 제한은 clientIp 기준이다. 헤더를 믿지 않는 설정에서 X-Forwarded-For로 IP를 바꿀 수 없어야 한다 */
class ClientIpTest {

    private val request = MockHttpServletRequest().apply {
        remoteAddr = "10.0.0.1"
        addHeader("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
    }

    private fun webConfig(trust: Boolean) = WebConfig("http://localhost:3000", trust, mock(AuthService::class.java))

    @Test
    fun `헤더를 믿지 않으면 위조한 X-Forwarded-For를 무시하고 접속 주소를 쓴다`() {
        assertEquals("10.0.0.1", webConfig(trust = false).clientIp(request))
    }

    @Test
    fun `헤더를 믿으면 X-Forwarded-For의 첫 값을 쓴다`() {
        assertEquals("203.0.113.9", webConfig(trust = true).clientIp(request))
    }

    @Test
    fun `헤더를 믿어도 값이 비어 있으면 접속 주소를 쓴다`() {
        val empty = MockHttpServletRequest().apply { remoteAddr = "10.0.0.1"; addHeader("X-Forwarded-For", " ") }
        assertEquals("10.0.0.1", webConfig(trust = true).clientIp(empty))
    }
}
