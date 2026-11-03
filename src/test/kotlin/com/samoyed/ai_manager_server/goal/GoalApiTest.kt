package com.samoyed.ai_manager_server.goal

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import org.junit.jupiter.api.Assertions.assertEquals
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
import org.springframework.test.web.servlet.ResultActionsDsl
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put

/** 목표 도구(통화 /tools 경유)와 화면용 /goals API */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class GoalApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var goals: GoalRepository
    @Autowired lateinit var activities: ActivityLogRepository
    @Autowired lateinit var service: GoalService
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        listOf("activity_logs", "goals").forEach { jdbc.sql("delete from $it").update() }
    }

    @Test
    fun `목표를 등록하고 활동을 연결하면 이번 주 진행에 반영된다`() {
        call("create_goal", """{"title":"정보처리기사 합격","category":"STUDY","weekly_target_minutes":600,"due_date":"2099-12-31"}""").andExpect {
            jsonPath("$.ok") { value(true) }
            jsonPath("$.summary") { value("목표 등록: 정보처리기사 합격") }
        }
        val goal = goals.findAll().single()

        call("log_activity", """{"description":"기출 풀이","category":"STUDY","minutes":120,"goal_id":"${goal.id}"}""").andExpect {
            jsonPath("$.summary") { value("활동 기록: 기출 풀이 120분 → 정보처리기사 합격") }
        }
        call("log_activity", """{"description":"관련 없는 공부","category":"STUDY","minutes":60}""")

        assertEquals(120, service.progress(goal).weekMinutes)
        call("list_goals", "{}").andExpect {
            jsonPath("$.result") { value(org.hamcrest.Matchers.containsString("이번 주 2시간 / 목표 10시간")) }
        }
        mvc.get("/goals") { header(HEADER, PASSCODE) }.andExpect {
            jsonPath("$[0].thisWeekMinutes") { value(120) }
            jsonPath("$[0].weeklyTargetMinutes") { value(600) }
        }
    }

    @Test
    fun `없거나 끝난 목표에는 활동을 연결하지 않는다`() {
        val done = service.create("끝난 목표", ActivityCategory.STUDY, null, null, null)
        service.update(done.id, GoalChange(status = GoalStatus.DONE))

        call("log_activity", """{"description":"공부","category":"STUDY","goal_id":"${done.id}"}""").andExpect { jsonPath("$.ok") { value(false) } }
        call("log_activity", """{"description":"공부","category":"STUDY","goal_id":"00000000-0000-0000-0000-000000000000"}""").andExpect { jsonPath("$.ok") { value(false) } }
        assertEquals(0, activities.count())
    }

    @Test
    fun `목표를 달성 처리하고 진행 중인 목표는 10개까지만 둔다`() {
        val goal = service.create("토익 800", ActivityCategory.STUDY, null, null, null)
        call("update_goal", """{"id":"${goal.id}","status":"DONE"}""").andExpect {
            jsonPath("$.summary") { value("목표 수정: 토익 800 (달성)") }
        }
        assertEquals(GoalStatus.DONE, goals.findById(goal.id).get().status)

        repeat(MAX_ACTIVE_GOALS) { service.create("목표 $it", ActivityCategory.OTHER, null, null, null) }
        call("create_goal", """{"title":"하나 더","category":"OTHER"}""").andExpect {
            jsonPath("$.ok") { value(false) }
            jsonPath("$.error") { value(org.hamcrest.Matchers.containsString("최대 10개")) }
        }
        // 끝난 목표를 다시 진행 중으로 돌리는 것도 한도를 지킨다
        mvc.put("/goals/${goal.id}") {
            header(HEADER, PASSCODE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"status":"ACTIVE"}"""
        }.andExpect { status { isBadRequest() } }
    }

    @Test
    fun `화면에서 목표를 만들고 잘못된 값은 400`() {
        mvc.post("/goals") {
            header(HEADER, PASSCODE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"헬스 주 3회","category":"EXERCISE","weeklyTargetMinutes":180}"""
        }.andExpect {
            status { isCreated() }
            jsonPath("$.progress") { value("헬스 주 3회: 이번 주 0분 / 목표 3시간") }
        }
        mvc.post("/goals") {
            header(HEADER, PASSCODE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"title":"x","category":"EXERCISE","weeklyTargetMinutes":0}"""
        }.andExpect { status { isBadRequest() } }
        mvc.post("/goals") {
            header(HEADER, PASSCODE)
            contentType = MediaType.APPLICATION_JSON
            content = """{"category":"EXERCISE"}"""
        }.andExpect { status { isBadRequest() } }
    }

    private fun call(name: String, args: String): ResultActionsDsl = mvc.post("/tools/$name") {
        header(HEADER, PASSCODE)
        contentType = MediaType.APPLICATION_JSON
        content = """{"args":$args}"""
    }

    private companion object {
        const val HEADER = "X-App-Passcode"
        const val PASSCODE = "test-passcode"
    }
}
