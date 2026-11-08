package com.samoyed.ai_manager_server.config

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestControllerAdvice

/** 잘못된 요청은 {"error": "invalid_request", "fields": {...}} 형태로 돌려준다. 인증 필터의 401 응답과 같은 모양 */
data class InvalidRequestResponse(val error: String = "invalid_request", val fields: Map<String, String> = emptyMap())

@RestControllerAdvice
class ApiExceptionHandler {
    private val log = LoggerFactory.getLogger(javaClass)

    @ExceptionHandler(MethodArgumentNotValidException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun invalidFields(e: MethodArgumentNotValidException) = InvalidRequestResponse(
        fields = e.bindingResult.fieldErrors.associate { it.field to (it.defaultMessage ?: "올바르지 않은 값입니다") },
    )

    /** JSON 형식 오류, 필수 필드 누락 등. 원문은 로그에만 남긴다 */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    fun unreadable(e: HttpMessageNotReadableException): InvalidRequestResponse {
        log.info("unreadable request body: {}", e.message)
        return InvalidRequestResponse()
    }
}
