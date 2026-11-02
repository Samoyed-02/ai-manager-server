package com.samoyed.ai_manager_server.claude

import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.errors.CredentialResolutionException
import com.anthropic.errors.NoCredentialsException
import com.anthropic.errors.SseException
import java.io.IOException

/**
 * 클라이언트에 보여줄 오류 종류. 예외 원문(상태 코드, 응답 본문, request_id 등)은 로그에만 남기고
 * 응답과 DB에는 userMessage만 쓴다.
 */
enum class ClaudeErrorKind(val userMessage: String, val retryable: Boolean) {
    CONFIG("서버 설정 문제로 처리할 수 없습니다", false),
    BAD_REQUEST("요청을 처리할 수 없습니다 (입력이 너무 길거나 형식 문제)", false),
    RATE_LIMITED("요청이 많아요. 잠시 후 다시 시도해 주세요", true),
    UNAVAILABLE("AI 서비스가 일시적으로 불안정합니다", true),
    NETWORK("AI 서비스 연결에 실패했습니다", true),
    UNKNOWN("처리 중 오류가 발생했습니다", true),
}

fun classifyClaudeError(e: Throwable): ClaudeErrorKind = when (e) {
    is NoCredentialsException, is CredentialResolutionException -> ClaudeErrorKind.CONFIG
    // 스트리밍 도중 받은 error 이벤트 (overloaded 등)
    is SseException -> ClaudeErrorKind.UNAVAILABLE
    is AnthropicServiceException -> when (e.statusCode()) {
        401, 403, 404 -> ClaudeErrorKind.CONFIG
        400, 413, 422 -> ClaudeErrorKind.BAD_REQUEST
        429 -> ClaudeErrorKind.RATE_LIMITED
        in 500..599 -> ClaudeErrorKind.UNAVAILABLE
        else -> ClaudeErrorKind.UNKNOWN
    }
    is AnthropicIoException, is IOException -> ClaudeErrorKind.NETWORK
    else -> ClaudeErrorKind.UNKNOWN
}
