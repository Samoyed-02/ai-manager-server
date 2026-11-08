package com.samoyed.ai_manager_server.auth

import com.samoyed.ai_manager_server.config.SESSION_ATTRIBUTE
import com.samoyed.ai_manager_server.config.WebConfig
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import org.springframework.web.server.ResponseStatusException
import java.time.OffsetDateTime
import java.util.UUID

data class LoginRequest(
    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = 200, message = "최대 {max}자까지 입력할 수 있습니다")
    val password: String,

    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = 10, message = "최대 {max}자까지 입력할 수 있습니다")
    val totp: String,

    /** 기기 목록에서 알아보기 위한 이름. 예: "아이폰", "노트북 크롬" */
    @field:Size(max = MAX_DEVICE_NAME, message = "최대 {max}자까지 입력할 수 있습니다")
    val deviceName: String? = null,
)

data class LoginResponse(val token: String, val sessionId: UUID, val expiresAt: OffsetDateTime)
data class SessionDto(
    val id: UUID,
    val deviceName: String,
    val createdAt: OffsetDateTime,
    val lastUsedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime,
    val current: Boolean,
)

@RestController
@RequestMapping("/auth")
class AuthController(private val auth: AuthService, private val web: WebConfig) {

    /** 실패 응답은 비밀번호와 코드 중 무엇이 틀렸는지 구분하지 않는다 */
    @PostMapping("/login")
    fun login(@Valid @RequestBody req: LoginRequest, http: HttpServletRequest): ResponseEntity<Any> =
        when (val result = auth.login(req.password, req.totp, req.deviceName?.trim()?.ifEmpty { null } ?: "이름 없는 기기", web.clientIp(http))) {
            is LoginResult.Success -> ResponseEntity.ok(LoginResponse(result.session.token, result.session.sessionId, result.session.expiresAt))
            LoginResult.InvalidCredentials -> ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(mapOf("error" to "invalid_credentials"))
            is LoginResult.Locked -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", result.retryAfterSeconds.toString())
                .body(mapOf("error" to "too_many_attempts", "retryAfterSeconds" to result.retryAfterSeconds))
        }

    /** 지금 기기만 로그아웃 */
    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(http: HttpServletRequest) {
        val session = currentSession(http)
        auth.revoke(session, session)
    }

    @GetMapping("/sessions")
    fun sessions(http: HttpServletRequest): List<SessionDto> {
        val current = currentSession(http)
        return auth.sessions().map { SessionDto(it.id, it.deviceName, it.createdAt, it.lastUsedAt, it.expiresAt, it.id == current) }
    }

    /** 다른 기기 하나를 끊는다 (잃어버린 휴대폰 등) */
    @DeleteMapping("/sessions/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun revoke(@PathVariable id: UUID, http: HttpServletRequest) {
        if (!auth.revoke(id, currentSession(http))) throw ResponseStatusException(HttpStatus.NOT_FOUND)
    }

    /** 지금 기기를 뺀 모든 기기를 끊는다 */
    @DeleteMapping("/sessions")
    fun revokeOthers(http: HttpServletRequest): Map<String, Int> = mapOf("revoked" to auth.revokeOthers(currentSession(http)))

    /** 인증 필터를 거친 요청에는 항상 세션이 있다. 없으면 필터 설정이 잘못된 것이므로 막는다 */
    private fun currentSession(http: HttpServletRequest): UUID =
        http.getAttribute(SESSION_ATTRIBUTE) as UUID? ?: throw ResponseStatusException(HttpStatus.FORBIDDEN, "로그인한 세션에서만 할 수 있습니다")
}
