package com.samoyed.ai_manager_server.config

import com.samoyed.ai_manager_server.auth.AuthService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.web.cors.CorsConfiguration
import org.springframework.web.cors.UrlBasedCorsConfigurationSource
import org.springframework.web.filter.CorsFilter
import org.springframework.web.filter.OncePerRequestFilter

/** 인증 필터가 요청에 붙여 두는 현재 세션 id */
const val SESSION_ATTRIBUTE = "authSessionId"

/** 인증 없이 열어 두는 경로. 헬스체크는 상태(UP/DOWN)만 돌려준다 */
private val PUBLIC_PATHS = setOf("/auth/login", "/actuator/health")

@Configuration
class WebConfig(
    @Value("\${app.allowed-origins}") private val origins: String,
    /** 앞단 프록시가 X-Forwarded-For를 덮어쓰는 환경에서만 true. 아니면 헤더를 위조해 시도 제한을 피할 수 있다 */
    @Value("\${app.trust-forwarded-for:false}") private val trustForwardedFor: Boolean,
    private val auth: AuthService,
) {
    fun clientIp(req: HttpServletRequest): String =
        (if (trustForwardedFor) req.getHeader("X-Forwarded-For")?.split(",")?.firstOrNull()?.trim()?.takeIf { it.isNotEmpty() } else null)
            ?: req.remoteAddr

    @Bean
    fun corsFilter(): FilterRegistrationBean<CorsFilter> {
        val config = CorsConfiguration().apply {
            allowedOrigins = origins.split(",").map { it.trim() }
            allowedMethods = listOf("GET", "POST", "PUT", "DELETE", "OPTIONS")
            allowedHeaders = listOf("*")
        }
        val source = UrlBasedCorsConfigurationSource().apply {
            registerCorsConfiguration("/**", config)
        }
        return FilterRegistrationBean(CorsFilter(source)).apply {
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }

    /** 개인 정보가 캐시되거나 다른 사이트로 새지 않게 하는 응답 헤더 */
    @Bean
    fun securityHeadersFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
                res.setHeader("Cache-Control", "no-store")
                res.setHeader("X-Content-Type-Options", "nosniff")
                res.setHeader("Referrer-Policy", "no-referrer")
                val https = req.isSecure || (trustForwardedFor && req.getHeader("X-Forwarded-Proto") == "https")
                if (https) res.setHeader("Strict-Transport-Security", "max-age=31536000")
                chain.doFilter(req, res)
            }
        }
        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply { order = Ordered.HIGHEST_PRECEDENCE + 1 }
    }

    /**
     * Authorization: Bearer <세션 토큰>을 확인한다. /auth/login, 헬스체크, CORS preflight만 인증 없이 통과한다
     */
    @Bean
    fun authFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(req: HttpServletRequest, res: HttpServletResponse, chain: FilterChain) {
                if (req.method == "OPTIONS" || req.requestURI in PUBLIC_PATHS) {
                    chain.doFilter(req, res)
                    return
                }
                val bearer = req.getHeader("Authorization")?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }?.substring(7)?.trim()
                val sessionId = bearer?.let { auth.authenticate(it) }
                if (sessionId != null) {
                    req.setAttribute(SESSION_ATTRIBUTE, sessionId)
                    chain.doFilter(req, res)
                } else {
                    res.status = 401
                    res.contentType = "application/json"
                    res.writer.write("""{"error":"unauthorized"}""")
                }
            }
        }
        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply { order = Ordered.HIGHEST_PRECEDENCE + 2 }
    }
}
