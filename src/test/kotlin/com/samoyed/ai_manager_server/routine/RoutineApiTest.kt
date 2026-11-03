package com.samoyed.ai_manager_server.routine

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestAuth
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLog
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate

/** 화면에서 쓰는 루틴·알림함·활동 기록 API */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class RoutineApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var testAuth: TestAuth
    @Autowired lateinit var routines: RoutineRepository
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var activities: ActivityLogRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        listOf("notifications", "routines", "activity_logs").forEach { jdbc.sql("delete from $it").update() }
    }

    @Test
    fun `루틴을 만들고 고치고 지운다`() {
        mvc.post("/routines") {
            header("Authorization", testAuth.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"아침 브리핑","kind":"BRIEFING","message":"오늘 할 일","time":"08:00","days":["MON","FRI"]}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.schedule") { value("월·금 08:00") }
            jsonPath("$.days.length()") { value(2) }
        }
        val id = routines.findAll().single().id

        mvc.put("/routines/$id") {
            header("Authorization", testAuth.bearer)
            contentType = MediaType.APPLICATION_JSON
            content = """{"enabled":false,"time":"7:30"}"""
        }.andExpect {
            status { isOk() }
            jsonPath("$.enabled") { value(false) }
            jsonPath("$.time") { value("07:30") }
        }
        mvc.get("/routines") { header("Authorization", testAuth.bearer) }.andExpect { jsonPath("$[0].title") { value("아침 브리핑") } }
        mvc.delete("/routines/$id") { header("Authorization", testAuth.bearer) }.andExpect { status { isNoContent() } }
        mvc.delete("/routines/$id") { header("Authorization", testAuth.bearer) }.andExpect { status { isNotFound() } }
    }

    @Test
    fun `잘못된 루틴은 400`() {
        listOf(
            """{"title":"x","kind":"REMINDER","message":"m","time":"24:00"}""",
            """{"title":"x","kind":"REMINDER","message":"m","time":"08:00","days":["MONDAY"]}""",
            """{"title":"x","kind":"REMINDER"}""",
        ).forEach { body ->
            mvc.post("/routines") {
                header("Authorization", testAuth.bearer)
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect { status { isBadRequest() } }
        }
        assertEquals(0, routines.count())
    }

    @Test
    fun `알림함은 읽지 않은 것만 볼 수 있고 읽음 처리한다`() {
        val first = notifications.save(Notification(null, "알림 1", "내용 1"))
        notifications.save(Notification(null, "알림 2", "내용 2"))

        mvc.post("/notifications/${first.id}/read") { header("Authorization", testAuth.bearer) }.andExpect { status { isNoContent() } }

        mvc.get("/notifications?unread=true") { header("Authorization", testAuth.bearer) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].title") { value("알림 2") }
        }
        mvc.get("/notifications") { header("Authorization", testAuth.bearer) }.andExpect { jsonPath("$.length()") { value(2) } }
        assertTrue(notifications.findById(first.id).get().readAt != null)
    }

    @Test
    fun `활동 기록은 기본 최근 7일을 보여 주고 지울 수 있다`() {
        val today = LocalDate.now(RoutineSchedule.ZONE)
        val recent = activities.save(ActivityLog(ActivityCategory.STUDY, "알고리즘", 120, today))
        activities.save(ActivityLog(ActivityCategory.EXERCISE, "오래전 운동", 60, today.minusDays(10)))

        mvc.get("/activities") { header("Authorization", testAuth.bearer) }.andExpect {
            jsonPath("$.length()") { value(1) }
            jsonPath("$[0].description") { value("알고리즘") }
        }
        mvc.get("/activities?from=${today.minusDays(30)}&to=$today") { header("Authorization", testAuth.bearer) }.andExpect { jsonPath("$.length()") { value(2) } }
        mvc.delete("/activities/${recent.id}") { header("Authorization", testAuth.bearer) }.andExpect { status { isNoContent() } }
        assertFalse(activities.existsById(recent.id))
    }

}
