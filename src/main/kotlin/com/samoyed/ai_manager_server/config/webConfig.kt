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

@Configuration
class WebConfig(
    @Value("\${app.allowed-origins}") private val origins: String,
    @Value("\${app.passcode}") private val passcode: String,
) {
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
                if (req.method == "OPTIONS" || req.getHeader("X-App-Passcode") == passcode) {
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