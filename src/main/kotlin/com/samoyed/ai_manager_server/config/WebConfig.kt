package com.samoyed.ai_manager_server.config

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
import java.security.MessageDigest

@Configuration
class WebConfig(
    @Value("\${app.allowed-origins}") private val origins: String,
    @Value("\${app.passcode}") private val passcode: String,
) {
    private val expectedPasscode = passcode.toByteArray(Charsets.UTF_8)

    /** 상수 시간 비교. 비교 시간은 입력 길이에만 좌우되어 일치하는 앞부분 길이나 정답 길이가 드러나지 않는다 */
    private fun passcodeMatches(given: String?): Boolean =
        given != null && MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), expectedPasscode)

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

    @Bean
    fun passcodeFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter: OncePerRequestFilter = object : OncePerRequestFilter() {
            override fun doFilterInternal(
                req: HttpServletRequest,
                res: HttpServletResponse,
                chain: FilterChain,
            ) {
                if (req.method == "OPTIONS" || passcodeMatches(req.getHeader("X-App-Passcode"))) {
                    chain.doFilter(req, res)
                } else {
                    res.status = 401
                    res.contentType = "application/json"
                    res.writer.write("""{"error":"unauthorized"}""")
                }
            }
        }
        return FilterRegistrationBean(filter).apply {
            order = Ordered.HIGHEST_PRECEDENCE + 1
        }
    }
}