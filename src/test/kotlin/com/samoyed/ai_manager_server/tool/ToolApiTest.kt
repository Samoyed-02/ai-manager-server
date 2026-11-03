package com.samoyed.ai_manager_server.tool

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.routine.RoutineKind
import com.samoyed.ai_manager_server.routine.RoutineRepository
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
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
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

/** 통화(Gemini)가 클라이언트를 거쳐 부르는 도구 API */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ToolApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var routines: RoutineRepository
    @Autowired lateinit var activities: ActivityLogRepository
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        listOf("notifications", "routines", "activity_logs", "memories").forEach { jdbc.sql("delete from $it").update() }
    }

    @Test
    fun `도구 목록을 함수 선언 형태로 준다`() {
        mvc.get("/tools") { header(HEADER, PASSCODE) }.andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(10) }
            listOf(
                "create_goal", "create_routine", "create_task", "delete_routine", "list_goals",
                "list_routines", "log_activity", "save_memory", "update_goal", "update_routine",
            )
                .forEachIndexed { i, name -> jsonPath("$[$i].name") { value(name) } }
            jsonPath("$[1].parameters.type") { value("object") }
            jsonPath("$[1].parameters.required.length()") { value(4) }
            listOf("title", "kind", "message", "time").forEachIndexed { i, field -> jsonPath("$[1].parameters.required[$i]") { value(field) } }
        }
    }

    @Test
    fun `루틴을 등록하면 다음 실행 시각이 잡힌다`() {
        call("create_routine", """{"title":"아침 브리핑","kind":"BRIEFING","message":"오늘 할 일 정리","time":"8:00","days":["MON","WED"]}""")
            .andExpect {
                status { isOk() }
                jsonPath("$.ok") { value(true) }
                jsonPath("$.summary") { value("루틴 등록: 아침 브리핑 (월·수 08:00)") }
            }

        val routine = routines.findAll().single()
        assertAll(
            { assertEquals(RoutineKind.BRIEFING, routine.kind) },
            { assertEquals(LocalTime.of(8, 0), routine.timeOfDay) },
            { assertEquals("MON,WED", routine.days) },
            { assertTrue(routine.nextRunAt.isAfter(OffsetDateTime.now())) },
        )
    }

    @Test
    fun `입력이 잘못되면 200과 ok=false로 사유를 준다`() {
        call("create_routine", """{"title":"알림","kind":"REMINDER","message":"물 마시기","time":"25:00"}""").andExpect {
            status { isOk() }
            jsonPath("$.ok") { value(false) }
            jsonPath("$.error") { value("time 은(는) 00:00~23:59 사이여야 해.") }
        }
        call("create_routine", """{"title":"알림","kind":"ALARM","message":"물 마시기","time":"09:00"}""").andExpect {
            jsonPath("$.ok") { value(false) }
        }
        call("update_routine", """{"id":"not-a-uuid","enabled":false}""").andExpect {
            jsonPath("$.ok") { value(false) }
        }
        assertEquals(0, routines.count())
    }

    @Test
    fun `루틴을 끄고 지운다`() {
        call("create_routine", """{"title":"밤 기록","kind":"REMINDER","message":"오늘 공부 기록해","time":"22:00"}""")
        val id = routines.findAll().single().id

        call("update_routine", """{"id":"$id","enabled":false}""").andExpect {
            jsonPath("$.summary") { value("루틴 수정: 밤 기록 (꺼짐)") }
        }
        assertFalse(routines.findById(id).get().enabled)

        call("list_routines", "{}").andExpect { jsonPath("$.result") { value(org.hamcrest.Matchers.containsString(id.toString())) } }
        call("delete_routine", """{"id":"$id"}""").andExpect { jsonPath("$.ok") { value(true) } }
        assertEquals(0, routines.count())
    }

    @Test
    fun `활동은 날짜를 생략하면 오늘 한국 날짜로 기록한다`() {
        call("log_activity", """{"description":"알고리즘 문제 풀이","category":"STUDY","minutes":120}""").andExpect {
            jsonPath("$.summary") { value("활동 기록: 알고리즘 문제 풀이 120분") }
        }
        val log = activities.findAll().single()
        assertAll(
            { assertEquals(LocalDate.now(RoutineSchedule.ZONE), log.occurredOn) },
            { assertEquals(120, log.minutes) },
            // 통화 중에는 대화가 아직 저장되지 않아 출처가 없다
            { assertNull(log.sourceConversationId) },
        )
        call("log_activity", """{"description":"헬스","category":"EXERCISE","minutes":0}""").andExpect { jsonPath("$.ok") { value(false) } }
    }

    @Test
    fun `민감 정보는 기억으로 저장하지 않는다`() {
        call("save_memory", """{"category":"FACT","content":"사용자의 비밀번호: abc123!"}""").andExpect { jsonPath("$.ok") { value(false) } }
        call("save_memory", """{"category":"PREFERENCE","content":"사용자는 짧은 답을 좋아한다"}""").andExpect { jsonPath("$.ok") { value(true) } }
        assertEquals(listOf("사용자는 짧은 답을 좋아한다"), memories.findAll().map { it.content })
    }

    @Test
    fun `없는 도구는 404, 패스코드가 없으면 401`() {
        call("send_email", "{}").andExpect { status { isNotFound() } }
        mvc.get("/tools").andExpect { status { isUnauthorized() } }
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
