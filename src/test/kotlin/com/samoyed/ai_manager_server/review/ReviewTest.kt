package com.samoyed.ai_manager_server.review

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLog
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.goal.GoalService
import com.samoyed.ai_manager_server.profile.ContextService
import com.samoyed.ai_manager_server.routine.NotificationRepository
import com.samoyed.ai_manager_server.routine.Routine
import com.samoyed.ai_manager_server.routine.RoutineKind
import com.samoyed.ai_manager_server.routine.RoutineRepository
import com.samoyed.ai_manager_server.routine.RoutineRunner
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import com.samoyed.ai_manager_server.task.Task
import com.samoyed.ai_manager_server.task.TaskRepository
import com.samoyed.ai_manager_server.task.anyArg
import com.samoyed.ai_manager_server.task.claudeResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.time.LocalDate
import java.time.LocalTime
import java.time.OffsetDateTime

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ReviewTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var service: ReviewService
    @Autowired lateinit var runner: RoutineRunner
    @Autowired lateinit var goals: GoalService
    @Autowired lateinit var activities: ActivityLogRepository
    @Autowired lateinit var tasks: TaskRepository
    @Autowired lateinit var reviews: ReviewRepository
    @Autowired lateinit var routines: RoutineRepository
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var context: ContextService
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val sent = mutableListOf<MessageCreateParams>()
    private val today: LocalDate = LocalDate.now(RoutineSchedule.ZONE)

    @BeforeEach
    fun setUp() {
        listOf("reviews", "notifications", "routines", "activity_logs", "goals", "documents", "tasks").forEach { jdbc.sql("delete from $it").update() }
        `when`(client.messages()).thenReturn(messageService)
        sent.clear()
    }

    @Test
    fun `지난 7일 통계를 서버가 계산한다`() {
        val goal = goals.create("정보처리기사", ActivityCategory.STUDY, null, 600, null)
        log(ActivityCategory.STUDY, 120, today, goal.id)
        log(ActivityCategory.STUDY, 60, today.minusDays(2))
        log(ActivityCategory.EXERCISE, 30, today.minusDays(6))
        // 이전 7일 (비교용)과 기간 밖
        log(ActivityCategory.STUDY, 90, today.minusDays(8))
        log(ActivityCategory.STUDY, 999, today.minusDays(20))
        doneTask(daysAgo = 1)
        doneTask(daysAgo = 10)

        val stats = service.stats(today)

        assertAll(
            { assertEquals(today.minusDays(6), stats.periodStart) },
            { assertEquals(Triple(180L, 90L, 2), stats.categories[ActivityCategory.STUDY]) },
            { assertEquals(Triple(30L, 0L, 1), stats.categories[ActivityCategory.EXERCISE]) },
            { assertEquals(listOf(Triple("정보처리기사", 120L, 600)), stats.goals) },
            { assertEquals(4, stats.daysWithoutLogs) },
            { assertEquals(1, stats.tasksDone) },
            { assertTrue(stats.toText().contains("- 공부: 3시간 (2건, 지난 기간 대비 +1시간 30분)"), stats.toText()) },
            { assertTrue(stats.toText().contains("- 목표 '정보처리기사': 2시간 / 목표 10시간 (20%)"), stats.toText()) },
        )
    }

    @Test
    fun `회고 루틴은 통계를 담아 회고를 쓰고 저장한 뒤 알림함에 넣는다`() {
        log(ActivityCategory.STUDY, 120, today)
        val routine = routines.save(Routine("주간 회고", RoutineKind.REVIEW, "다음 주 제안", "SUN", LocalTime.of(21, 0), OffsetDateTime.now().minusMinutes(1)))
        replies(claudeResponse(text = "이번 주는 공부 2시간. 다음 주는 매일 30분씩."))

        runner.poll()

        val review = reviews.findAll().single()
        val request = sent.single()
        assertAll(
            { assertEquals("이번 주는 공부 2시간. 다음 주는 매일 30분씩.", review.content) },
            { assertEquals(routine.id, review.routineId) },
            { assertTrue(review.stats.contains("\"minutes\":120"), review.stats) },
            { assertTrue(request.messages().single().content().asString().contains("- 공부: 2시간 (1건")) },
            { assertTrue(request.messages().single().content().asString().contains("다음 주 제안")) },
            { assertEquals(review.content, notifications.findAll().single().body) },
            // 다음 대화의 맥락에 최근 회고가 들어간다
            { assertTrue(context.forClaude().contains("## 최근 주간 회고"), context.forClaude()) },
            { assertTrue(context.forVoice().contains("다음 주는 매일 30분씩."), context.forVoice()) },
        )
    }

    @Test
    fun `거절되면 회고는 저장하지 않고 통계만 알린다`() {
        log(ActivityCategory.STUDY, 30, today)
        routines.save(Routine("주간 회고", RoutineKind.REVIEW, "회고", "", LocalTime.of(21, 0), OffsetDateTime.now().minusMinutes(1)))
        replies(claudeResponse(text = "", stopReason = StopReason.REFUSAL))

        runner.poll()

        assertAll(
            { assertEquals(0, reviews.count()) },
            { assertTrue(notifications.findAll().single().body.startsWith("회고를 만들지 못했어요.")) },
        )
    }

    @Test
    fun `맥락에 진행 중인 목표와 이번 주 진행이 id와 함께 들어간다`() {
        val goal = goals.create("정보처리기사", ActivityCategory.STUDY, null, 600, null)
        log(ActivityCategory.STUDY, 90, today, goal.id)

        val text = context.forClaude()

        assertTrue(text.contains("- 정보처리기사: 이번 주 1시간 30분 / 목표 10시간 [id=${goal.id}]"), text)
    }

    private fun log(category: ActivityCategory, minutes: Int, date: LocalDate, goalId: java.util.UUID? = null) =
        activities.save(ActivityLog(category, "기록", minutes, date, goalId = goalId))

    private fun doneTask(daysAgo: Long) {
        val task = tasks.save(Task("document", "문서"))
        jdbc.sql("update tasks set status = 'DONE', finished_at = now() - :d * interval '1 day' where id = :id")
            .param("d", daysAgo).param("id", task.id).update()
    }

    private fun replies(vararg responses: com.anthropic.models.messages.Message) {
        `when`(messageService.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            sent += it.getArgument<MessageCreateParams>(0)
            responses[(sent.size - 1).coerceAtMost(responses.size - 1)]
        }
    }
}
