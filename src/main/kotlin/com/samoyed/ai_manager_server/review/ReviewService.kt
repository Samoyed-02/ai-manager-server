package com.samoyed.ai_manager_server.review

import com.anthropic.client.AnthropicClient
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.claude.createLogged
import com.samoyed.ai_manager_server.claude.text
import com.samoyed.ai_manager_server.goal.GoalRepository
import com.samoyed.ai_manager_server.goal.GoalStatus
import com.samoyed.ai_manager_server.goal.formatMinutes
import com.samoyed.ai_manager_server.profile.ContextService
import com.samoyed.ai_manager_server.routine.Routine
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import com.samoyed.ai_manager_server.task.TaskRepository
import com.samoyed.ai_manager_server.task.TaskStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.LocalDate

private val CLAUDE_TIMEOUT: Duration = Duration.ofMinutes(2)
private const val PERIOD_DAYS = 7L
private const val MAX_LOG_LINES = 60

private const val REVIEW_SYSTEM = """
너는 사용자의 개인 AI 매니저야. 지난 7일을 돌아보는 주간 회고를 써.
- 통계 숫자는 서버가 계산한 정확한 값이니 그대로 쓰고, 새로 계산하거나 지어내지 마.
- 구성: 한 줄 요약, 잘한 점, 아쉬운 점, 다음 주 제안 2~3개(구체적이고 실행 가능하게). 전체 10줄 이내 한국어.
- 기록이 적으면 꾸짖지 말고, 기록을 남기기 쉬운 방법을 제안해.
"""

/** 기간 통계. 숫자는 모두 서버가 계산한다 */
data class ReviewStats(
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    /** 분류별 [이번 기간 분, 이전 기간 분, 이번 기간 건수] */
    val categories: Map<ActivityCategory, Triple<Long, Long, Int>>,
    /** 진행 중인 목표별 (제목, 기간 분, 주간 목표 분) */
    val goals: List<Triple<String, Long, Int?>>,
    val daysWithoutLogs: Int,
    val tasksDone: Long,
) {
    fun toJson(): String = jsonMapper().writeValueAsString(
        mapOf(
            "period_start" to periodStart.toString(),
            "period_end" to periodEnd.toString(),
            "categories" to categories.map { (c, v) ->
                mapOf("category" to c.name, "minutes" to v.first, "previous_minutes" to v.second, "count" to v.third)
            },
            "goals" to goals.map { (title, minutes, target) -> mapOf("title" to title, "minutes" to minutes, "weekly_target_minutes" to target) },
            "days_without_logs" to daysWithoutLogs,
            "tasks_done" to tasksDone,
        )
    )

    /** 프롬프트와 맥락에 넣을 사람이 읽는 형태 */
    fun toText(): String = buildString {
        appendLine("기간: $periodStart ~ $periodEnd")
        if (categories.isEmpty()) appendLine("- 활동 기록 없음")
        categories.forEach { (c, v) ->
            val diff = v.first - v.second
            val sign = if (diff >= 0) "+" else "-"
            appendLine("- ${c.label}: ${formatMinutes(v.first)} (${v.third}건, 지난 기간 대비 $sign${formatMinutes(kotlin.math.abs(diff))})")
        }
        goals.forEach { (title, minutes, target) ->
            val rate = target?.let { " / 목표 ${formatMinutes(it.toLong())} (${minutes * 100 / it}%)" } ?: ""
            appendLine("- 목표 '$title': ${formatMinutes(minutes)}$rate")
        }
        appendLine("- 기록이 없는 날: ${daysWithoutLogs}일")
        appendLine("- 완료된 문서 작업: ${tasksDone}건")
    }.trimEnd()
}

/** 주간 회고. 통계는 서버가, 해석과 제안은 Claude가 쓴다 */
@Service
class ReviewService(
    private val activities: ActivityLogRepository,
    private val goals: GoalRepository,
    private val tasks: TaskRepository,
    private val reviews: ReviewRepository,
    private val context: ContextService,
    private val client: AnthropicClient,
    @Value("\${claude.model}") private val model: String,
) {
    /** today를 끝으로 하는 지난 7일 통계 (한국 날짜) */
    fun stats(today: LocalDate = LocalDate.now(RoutineSchedule.ZONE)): ReviewStats {
        val start = today.minusDays(PERIOD_DAYS - 1)
        val logs = activities.findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(start, today)
        val previous = activities.findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(start.minusDays(PERIOD_DAYS), start.minusDays(1))
        val categories = ActivityCategory.entries.mapNotNull { c ->
            val now = logs.filter { it.category == c }
            val before = previous.filter { it.category == c }
            if (now.isEmpty() && before.isEmpty()) null
            else c to Triple(now.sumOf { (it.minutes ?: 0).toLong() }, before.sumOf { (it.minutes ?: 0).toLong() }, now.size)
        }.toMap()
        val goalStats = goals.findByStatusOrderByCreatedAtAsc(GoalStatus.ACTIVE).map { goal ->
            Triple(goal.title, activities.sumMinutesForGoal(goal.id, start, today), goal.weeklyTargetMinutes)
        }
        val loggedDays = logs.map { it.occurredOn }.toSet()
        val from = start.atStartOfDay(RoutineSchedule.ZONE).toOffsetDateTime()
        val to = today.plusDays(1).atStartOfDay(RoutineSchedule.ZONE).toOffsetDateTime()
        return ReviewStats(
            start, today, categories, goalStats,
            daysWithoutLogs = (PERIOD_DAYS - loggedDays.size).toInt(),
            tasksDone = tasks.countByStatusAndFinishedAtBetween(TaskStatus.DONE, from, to),
        )
    }

    /** 회고를 써서 저장하고 본문을 돌려준다. Claude 호출 오류는 그대로 던진다(루틴 실행기가 재시도를 정한다) */
    fun generate(routine: Routine): String {
        val stats = stats()
        val logs = activities.findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(stats.periodStart, stats.periodEnd)
            .take(MAX_LOG_LINES)
            .joinToString("\n") { "- ${it.occurredOn} [${it.category.label}] ${it.description}" + (it.minutes?.let { m -> " (${m}분)" } ?: "") }
            .ifBlank { "(없음)" }
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(2048)
            .system(REVIEW_SYSTEM.trimIndent() + "\n\n" + context.forClaude())
            .addUserMessage("## 지난 7일 통계 (서버 계산)\n${stats.toText()}\n\n## 지난 7일 활동 기록\n$logs\n\n## 추가 지시\n${routine.message}")
            .build()
        val message = client.createLogged(params, "review", routine.id, model, CLAUDE_TIMEOUT)
        val text = message.text()
        if (message.stopReason().orElse(null) == StopReason.REFUSAL || text.isEmpty()) {
            return "회고를 만들지 못했어요. 루틴 내용을 바꾸거나 다음 주에 다시 확인해 주세요.\n\n${stats.toText()}"
        }
        reviews.save(Review(stats.periodStart, stats.periodEnd, stats.toJson(), text, routine.id))
        return text
    }
}
