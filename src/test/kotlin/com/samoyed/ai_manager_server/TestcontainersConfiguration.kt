package com.samoyed.ai_manager_server

import com.samoyed.ai_manager_server.auth.AuthService
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    /** 개발(compose.yaml)·운영 DB와 같은 메이저 버전을 쓴다. 바꿀 때는 세 곳을 함께 맞춘다 */
    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"))
    }

    @Bean
    fun testAuth(auth: AuthService) = TestAuth(auth)
}

/** API 테스트용 로그인 세션. 컨텍스트마다 한 번 발급해 Authorization 헤더로 쓴다 */
class TestAuth(private val auth: AuthService) {
    val bearer: String by lazy { "Bearer " + auth.issueSession("test").token }
}
