package com.samoyed.ai_manager_server.auth

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID

/** 이 기간 동안 쓰지 않은 세션은 만료된다 */
val SESSION_IDLE: Duration = Duration.ofDays(30)
/** 써도 만든 뒤 이 기간이 지나면 만료된다 */
val SESSION_MAX: Duration = Duration.ofDays(90)
/** last_used_at을 매 요청마다 쓰지 않도록 이 간격보다 오래됐을 때만 갱신한다 */
private val TOUCH_INTERVAL: Duration = Duration.ofMinutes(5)

/** 이 시간 안에 같은 IP에서 MAX_FAILURES_PER_IP번 실패하면 막는다. 전체 실패가 MAX_FAILURES_TOTAL번이면 모두 막는다 */
val LOCKOUT_WINDOW: Duration = Duration.ofMinutes(15)
const val MAX_FAILURES_PER_IP = 5
const val MAX_FAILURES_TOTAL = 50

const val MAX_DEVICE_NAME = 100

data class IssuedSession(val sessionId: UUID, val token: String, val expiresAt: OffsetDateTime)
data class SessionInfo(val id: UUID, val deviceName: String, val createdAt: OffsetDateTime, val lastUsedAt: OffsetDateTime, val expiresAt: OffsetDateTime)

sealed interface LoginResult {
    data class Success(val session: IssuedSession) : LoginResult
    data object InvalidCredentials : LoginResult
    data class Locked(val retryAfterSeconds: Long) : LoginResult
}

/**
 * 비밀번호(bcrypt) + 인증 앱 코드(TOTP)로 로그인하고 기기별 세션 토큰을 발급한다.
 * 토큰 원문은 응답으로 한 번만 주고, DB에는 SHA-256 해시만 둔다
 */
@Service
class AuthService(
    private val jdbc: JdbcClient,
    @Value("\${app.password-hash}") passwordHash: String,
    @Value("\${app.totp-secret}") totpSecret: String,
) {
    private val log = LoggerFactory.getLogger("auth")
    private val encoder = BCryptPasswordEncoder(BCRYPT_STRENGTH)
    private val random = SecureRandom()

    private val passwordHash: String = passwordHash.trim()
    private val totpKey: ByteArray

    /** 설정이 잘못됐으면 서버가 뜨지 않는다 */
    init {
        require(Regex("""^\$2[aby]\$\d{2}\$[./A-Za-z0-9]{53}$""").matches(this.passwordHash)) {
            "APP_PASSWORD_HASH must be a bcrypt hash (./gradlew -q hashPassword --console=plain)"
        }
        totpKey = runCatching { Totp.decodeBase32(totpSecret.trim()) }.getOrNull()
            ?.takeIf { it.size >= 10 }
            ?: throw IllegalArgumentException("APP_TOTP_SECRET must be a base32 secret of at least 80 bits (./gradlew -q totpSecret --console=plain)")
    }

    private fun now(): OffsetDateTime = OffsetDateTime.now()

    fun login(password: String, totpCode: String, deviceName: String, ip: String): LoginResult {
        lockedFor(ip)?.let {
            log.warn("login blocked ip={} retryAfter={}s", ip, it)
            return LoginResult.Locked(it)
        }
        // 비밀번호와 코드를 모두 확인한 뒤 결과를 합친다. 어느 쪽이 틀렸는지 응답·시간으로 드러내지 않는다
        val passwordOk = encoder.matches(password, passwordHash)
        val counter = Totp.verify(totpKey, totpCode.trim(), now().toEpochSecond())
        val ok = passwordOk && counter != null && markTotpUsed(counter)
        recordAttempt(ip, ok)
        if (!ok) {
            log.warn("login failed ip={} device={}", ip, deviceName)
            return LoginResult.InvalidCredentials
        }
        val session = issueSession(deviceName)
        log.info("login ok ip={} device={} session={}", ip, deviceName, session.sessionId)
        return LoginResult.Success(session)
    }

    /** 새 세션을 만든다. 로그인 성공 뒤에만 부른다(테스트는 직접 부른다) */
    fun issueSession(deviceName: String): IssuedSession {
        val token = ByteArray(32).also { random.nextBytes(it) }.let { Base64.getUrlEncoder().withoutPadding().encodeToString(it) }
        val now = now()
        val expiresAt = now.plus(SESSION_MAX)
        val id = jdbc.sql(
            """
            insert into auth_sessions (token_hash, device_name, created_at, last_used_at, expires_at)
            values (:hash, :device, :now, :now, :expires)
            returning id
            """.trimIndent()
        ).param("hash", sha256(token)).param("device", deviceName.take(MAX_DEVICE_NAME)).param("now", now).param("expires", expiresAt)
            .query(UUID::class.java).single()
        return IssuedSession(id, token, expiresAt)
    }

    /** 유효한 토큰이면 세션 id. 만료(최대 기간·미사용 기간)·취소된 토큰은 null */
    fun authenticate(token: String): UUID? {
        if (token.isBlank() || token.length > 100) return null
        val now = now()
        val row = jdbc.sql(
            """
            select id, last_used_at from auth_sessions
             where token_hash = :hash and revoked_at is null and expires_at > :now and last_used_at > :idleSince
            """.trimIndent()
        ).param("hash", sha256(token)).param("now", now).param("idleSince", now.minus(SESSION_IDLE))
            .query { rs, _ -> rs.getObject("id", UUID::class.java) to rs.getObject("last_used_at", OffsetDateTime::class.java) }
            .optional().orElse(null) ?: return null
        if (row.second.isBefore(now.minus(TOUCH_INTERVAL))) {
            jdbc.sql("update auth_sessions set last_used_at = :now where id = :id").param("now", now).param("id", row.first).update()
        }
        return row.first
    }

    fun sessions(): List<SessionInfo> =
        jdbc.sql(
            """
            select id, device_name, created_at, last_used_at, expires_at from auth_sessions
             where revoked_at is null and expires_at > :now and last_used_at > :idleSince
             order by last_used_at desc
            """.trimIndent()
        ).param("now", now()).param("idleSince", now().minus(SESSION_IDLE))
            .query { rs, _ ->
                SessionInfo(
                    rs.getObject("id", UUID::class.java), rs.getString("device_name"),
                    rs.getObject("created_at", OffsetDateTime::class.java), rs.getObject("last_used_at", OffsetDateTime::class.java),
                    rs.getObject("expires_at", OffsetDateTime::class.java),
                )
            }.list()

    /** 세션 하나를 끊는다. 이미 끊겼거나 없으면 false */
    fun revoke(sessionId: UUID, by: UUID?): Boolean =
        (jdbc.sql("update auth_sessions set revoked_at = :now where id = :id and revoked_at is null")
            .param("now", now()).param("id", sessionId).update() == 1)
            .also { if (it) log.info("session revoked session={} by={}", sessionId, by) }

    /** 지금 세션을 뺀 나머지를 모두 끊는다 */
    fun revokeOthers(current: UUID): Int =
        jdbc.sql("update auth_sessions set revoked_at = :now where id <> :id and revoked_at is null")
            .param("now", now()).param("id", current).update()
            .also { log.info("sessions revoked count={} by={}", it, current) }

    /** 막혀 있으면 남은 초 */
    private fun lockedFor(ip: String): Long? {
        val since = now().minus(LOCKOUT_WINDOW)
        fun oldestFailure(byIp: Boolean, limit: Int): OffsetDateTime? {
            val failures = jdbc.sql(
                """
                select created_at from login_attempts
                 where not success and created_at > :since ${if (byIp) "and ip = :ip" else ""}
                 order by created_at desc limit :limit
                """.trimIndent()
            ).param("since", since).param("limit", limit).apply { if (byIp) param("ip", ip) }
                .query(OffsetDateTime::class.java).list()
            // 최근 limit번이 모두 창 안에 있으면, 그중 가장 오래된 실패가 창 밖으로 나갈 때 풀린다
            return if (failures.size >= limit) failures.last() else null
        }
        val blockedSince = listOfNotNull(oldestFailure(true, MAX_FAILURES_PER_IP), oldestFailure(false, MAX_FAILURES_TOTAL)).maxOrNull()
            ?: return null
        return Duration.between(now(), blockedSince.plus(LOCKOUT_WINDOW)).seconds.coerceAtLeast(1)
    }

    private fun recordAttempt(ip: String, success: Boolean) {
        jdbc.sql("insert into login_attempts (ip, success, created_at) values (:ip, :success, :now)")
            .param("ip", ip.take(64)).param("success", success).param("now", now()).update()
    }

    /** 같은 코드(counter)는 한 번만 쓸 수 있다. 이미 쓴 코드면 false */
    private fun markTotpUsed(counter: Long): Boolean =
        try {
            jdbc.sql("insert into totp_used_codes (counter, used_at) values (:counter, :now)")
                .param("counter", counter).param("now", now()).update() == 1
        } catch (e: DuplicateKeyException) {
            false
        }

    /** 오래된 기록 정리 */
    @Scheduled(fixedDelay = 3_600_000)
    fun cleanup() {
        val now = now()
        jdbc.sql("delete from login_attempts where created_at < :t").param("t", now.minusDays(30)).update()
        jdbc.sql("delete from totp_used_codes where used_at < :t").param("t", now.minusDays(1)).update()
        jdbc.sql("delete from auth_sessions where expires_at < :t or revoked_at < :t or last_used_at < :idle")
            .param("t", now.minusDays(30)).param("idle", now.minus(SESSION_IDLE).minusDays(30)).update()
    }

    private fun sha256(token: String): String =
        MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
}
