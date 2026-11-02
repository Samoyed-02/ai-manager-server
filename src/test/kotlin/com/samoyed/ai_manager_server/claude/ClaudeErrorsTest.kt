package com.samoyed.ai_manager_server.claude

import com.anthropic.core.JsonValue
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.BadRequestException
import com.anthropic.errors.InternalServerException
import com.anthropic.errors.NotFoundException
import com.anthropic.errors.RateLimitException
import com.anthropic.errors.UnauthorizedException
import com.anthropic.errors.UnexpectedStatusCodeException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import java.net.SocketTimeoutException

/** API 오류 응답 본문. 분류 결과에 이 내용이 섞이면 안 된다 */
val API_ERROR_BODY: JsonValue = JsonValue.from(
    mapOf("type" to "error", "error" to mapOf("type" to "api_error", "message" to "internal detail"), "request_id" to "req_secret")
)
private val NO_HEADERS: Headers = Headers.builder().build()

class ClaudeErrorsTest {

    @Test
    fun `SDK 예외를 상태 코드에 맞는 종류로 분류한다`() {
        assertAll(
            { assertEquals(ClaudeErrorKind.CONFIG, classifyClaudeError(UnauthorizedException.builder().headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
            { assertEquals(ClaudeErrorKind.CONFIG, classifyClaudeError(NotFoundException.builder().headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
            { assertEquals(ClaudeErrorKind.BAD_REQUEST, classifyClaudeError(BadRequestException.builder().headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
            { assertEquals(ClaudeErrorKind.RATE_LIMITED, classifyClaudeError(RateLimitException.builder().headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
            { assertEquals(ClaudeErrorKind.UNAVAILABLE, classifyClaudeError(InternalServerException.builder().statusCode(500).headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
            { assertEquals(ClaudeErrorKind.UNAVAILABLE, classifyClaudeError(UnexpectedStatusCodeException.builder().statusCode(529).headers(NO_HEADERS).body(API_ERROR_BODY).build())) },
        )
    }

    @Test
    fun `연결 오류와 그 밖의 예외를 분류한다`() {
        assertAll(
            { assertEquals(ClaudeErrorKind.NETWORK, classifyClaudeError(AnthropicIoException("connection reset"))) },
            { assertEquals(ClaudeErrorKind.NETWORK, classifyClaudeError(SocketTimeoutException("timeout"))) },
            { assertEquals(ClaudeErrorKind.UNKNOWN, classifyClaudeError(IllegalStateException("db down"))) },
        )
    }

    @Test
    fun `재시도 여부는 다시 해도 결과가 같은지에 따른다`() {
        assertAll(
            { assertEquals(false, ClaudeErrorKind.CONFIG.retryable) },
            { assertEquals(false, ClaudeErrorKind.BAD_REQUEST.retryable) },
            { assertEquals(true, ClaudeErrorKind.RATE_LIMITED.retryable) },
            { assertEquals(true, ClaudeErrorKind.UNAVAILABLE.retryable) },
        )
    }
}
