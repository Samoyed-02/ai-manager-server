package com.samoyed.ai_manager_server

import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import java.sql.DriverManager

/** 운영처럼 이전 버전 스키마에 데이터가 있는 DB에서 최신 마이그레이션까지 올라가는지 본다. 빈 DB는 다른 테스트가 매번 확인한다 */
class MigrationUpgradeTest {

    @Test
    fun `이전 버전 DB의 데이터를 지키며 최신 버전까지 올라간다`() {
        PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine")).use { db ->
            db.start()
            fun flyway(target: String) = Flyway.configure().dataSource(db.jdbcUrl, db.username, db.password).target(target).load()

            flyway("2").migrate()
            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                c.createStatement().execute(
                    """
                    insert into conversations (id, title) values ('00000000-0000-0000-0000-000000000001', '이전 대화');
                    insert into messages (conversation_id, role, content) values ('00000000-0000-0000-0000-000000000001', 'user', '안녕');
                    """.trimIndent()
                )
            }

            val result = flyway("latest").migrate()

            DriverManager.getConnection(db.jdbcUrl, db.username, db.password).use { c ->
                val row = c.createStatement().executeQuery("select content, status, actions from messages").apply { next() }
                assertAll(
                    { assertEquals(true, result.success) },
                    { assertEquals("안녕", row.getString("content")) },
                    // V4가 기존 행에 기본 상태를 채우고, V10 컬럼은 비어 있다
                    { assertEquals("COMPLETE", row.getString("status")) },
                    { assertEquals(null, row.getString("actions")) },
                )
            }
        }
    }
}
