package com.samoyed.ai_manager_server.profile

import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import com.samoyed.ai_manager_server.goal.GoalService
import com.samoyed.ai_manager_server.memory.MAX_MEMORIES_IN_CONTEXT
import com.samoyed.ai_manager_server.review.ReviewRepository
import com.samoyed.ai_manager_server.memory.Memory
import com.samoyed.ai_manager_server.memory.MemoryCategory
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.routine.NotificationRepository
import com.samoyed.ai_manager_server.routine.RoutineRepository
import com.samoyed.ai_manager_server.routine.scheduleLabel
import com.samoyed.ai_manager_server.task.DocumentRepository
import java.time.LocalDate
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

/** 통화에서 Gemini가 서버 도구(/tools)를 언제 쓰는지 */
private const val VOICE_TOOL_GUIDE =
    "도구: 말로 요청하면 기억 저장(save_memory), 루틴 등록·조회·수정·삭제(create_routine 등), 활동 기록(log_activity, 목표와 관련 있으면 goal_id 연결), " +
        "목표 등록·조회·수정(create_goal 등), " +
        "문서 작성 맡기기(create_task)를 할 수 있어. 처리했으면 무엇을 했는지 짧게 말하고, 삭제처럼 되돌릴 수 없는 일은 먼저 확인받아."

private const val CHAT_SUMMARIES = 5
private const val VOICE_MEMORIES = 40
private const val VOICE_SUMMARIES = 3
private const val UNREAD_NOTIFICATIONS = 3
private const val ACTIVITY_DAYS = 7L
private const val ACTIVITY_ITEMS = 10

@Service
class ContextService(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val memories: MemoryRepository,
    private val conversations: ConversationRepository,
    private val routines: RoutineRepository,
    private val notifications: NotificationRepository,
    private val activities: ActivityLogRepository,
    private val goals: GoalService,
    private val reviews: ReviewRepository,
) {
    private val zone = ZoneId.of("Asia/Seoul")
    private val fmt = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E) HH:mm", Locale.KOREAN)

    private fun now() = ZonedDateTime.now(zone).format(fmt)

    private fun recentDocTitles(n: Int) =
        documents.findAllByOrderByCreatedAtDesc(Limit.of(n)).map { it.title }

    private val dayFmt = DateTimeFormatter.ofPattern("M/d", Locale.KOREAN)

    /** 지금 대화의 요약은 빼고 최근 대화 요약 n개 */
    private fun recentSummaries(n: Int, exclude: UUID?): List<String> =
        conversations.findBySummaryIsNotNullOrderByUpdatedAtDesc(Limit.of(n + 1))
            .filter { it.id != exclude }
            .take(n)
            .map { "${it.updatedAt.atZoneSameInstant(zone).format(dayFmt)} ${it.channelLabel()}: ${it.summary!!.trim()}" }

    private fun Conversation.channelLabel() = if (channel == ConversationChannel.VOICE) "통화" else "채팅"

    /** 읽지 않은 알림(루틴 결과). 아직 푸시가 없어 대화를 시작할 때 AI가 먼저 전한다 */
    private fun StringBuilder.appendUnread(heading: String) {
        val unread = notifications.findByReadAtIsNullOrderByCreatedAtDesc(Limit.of(UNREAD_NOTIFICATIONS))
        if (unread.isEmpty()) return
        appendLine()
        appendLine(heading)
        unread.forEach {
            appendLine("- [${it.createdAt.atZoneSameInstant(zone).format(timeFmt)}] ${it.title}: ${it.body.trim().take(300)}")
        }
        val more = notifications.countByReadAtIsNull() - unread.size
        if (more > 0) appendLine("- 그 밖에 ${more}개 더 있음 (알림함)")
    }

    private fun StringBuilder.appendRoutines(heading: String) {
        val enabled = routines.findByEnabledTrueOrderByTimeOfDayAsc()
        if (enabled.isEmpty()) return
        appendLine()
        appendLine(heading)
        enabled.forEach { appendLine("- ${it.title} (${it.scheduleLabel()}, ${it.kind.label})") }
    }

    /** 진행 중인 목표와 이번 주 진행 시간 (서버 계산). id는 log_activity의 goal_id에 쓴다 */
    private fun StringBuilder.appendGoals(heading: String, withIds: Boolean) {
        val progress = goals.activeProgress()
        if (progress.isEmpty()) return
        appendLine()
        appendLine(heading)
        progress.forEach { appendLine("- ${it.line()}" + if (withIds) " [id=${it.goal.id}]" else "") }
    }

    /** 2주 안에 쓴 최근 회고. "지난주 회고에서 말한 대로"처럼 이어서 말할 수 있게 한다 */
    private fun StringBuilder.appendLatestReview(heading: String, maxChars: Int) {
        val review = reviews.findAllByOrderByCreatedAtDesc(Limit.of(1)).firstOrNull() ?: return
        if (review.createdAt.isBefore(java.time.OffsetDateTime.now().minusDays(14))) return
        appendLine()
        appendLine("$heading (${review.periodStart} ~ ${review.periodEnd})")
        appendLine(review.content.trim().take(maxChars))
    }

    /** 최근 7일 활동: 분류별 합계와 최근 항목 */
    private fun StringBuilder.appendActivities(heading: String, items: Int) {
        val today = LocalDate.now(zone)
        val logs = activities.findByOccurredOnBetweenOrderByOccurredOnDescCreatedAtDesc(today.minusDays(ACTIVITY_DAYS - 1), today)
        if (logs.isEmpty()) return
        appendLine()
        appendLine(heading)
        logs.groupBy { it.category }.forEach { (category, list) ->
            val minutes = list.mapNotNull { it.minutes }.sum()
            appendLine("- ${category.label}: ${list.size}건" + (if (minutes > 0) ", ${minutes / 60}시간 ${minutes % 60}분" else ""))
        }
        logs.take(items).forEach {
            appendLine("  - ${it.occurredOn.format(dayFmt)} ${it.description}" + (it.minutes?.let { m -> " (${m}분)" } ?: ""))
        }
    }

    private val timeFmt = DateTimeFormatter.ofPattern("M/d HH:mm", Locale.KOREAN)

    /** 카테고리별로 묶은 기억 목록 */
    private fun StringBuilder.appendMemories(list: List<Memory>) {
        list.groupBy { it.category }.toSortedMap(compareBy<MemoryCategory> { it.ordinal }).forEach { (category, items) ->
            appendLine("### ${category.label}")
            items.forEach { appendLine("- ${it.content}") }
        }
    }

    /**
     * Claude용: 전체 프로필 + 기억 + 최근 대화 요약 + 최근 문서 10개.
     * currentConversationId: 지금 대화의 요약은 이미 대화 기록에 있으므로 뺀다
     */
    fun forClaude(currentConversationId: UUID? = null): String = buildString {
        appendLine("## 현재 시각 (한국)")
        appendLine(now())
        val p = profiles.findById(1).orElse(null)
        if (p != null && p.fullMd.isNotBlank()) {
            appendLine()
            appendLine(p.fullMd.trim())
        }
        val remembered = memories.findAllByOrderByUpdatedAtDesc(Limit.of(MAX_MEMORIES_IN_CONTEXT))
        if (remembered.isNotEmpty()) {
            appendLine()
            appendLine("## 지난 대화에서 기억하는 것 (자동 정리)")
            appendMemories(remembered)
        }
        val summaries = recentSummaries(CHAT_SUMMARIES, currentConversationId)
        if (summaries.isNotEmpty()) {
            appendLine()
            appendLine("## 최근 대화 요약 (통화 포함)")
            summaries.forEach { appendLine("- $it") }
        }
        appendUnread("## 읽지 않은 알림 (사용자에게 먼저 전하고, 전했으면 다시 말하지 않아도 됨)")
        appendGoals("## 진행 중인 목표 (이번 주 = 월~일, 서버 계산)", withIds = true)
        appendLatestReview("## 최근 주간 회고", maxChars = 1_500)
        appendRoutines("## 등록된 루틴 (켜진 것)")
        appendActivities("## 최근 7일 활동 기록", ACTIVITY_ITEMS)
        val docs = recentDocTitles(10)
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("## 최근 맡겨서 작성된 문서")
            docs.forEach { appendLine("- $it") }
        }
    }

    /** Gemini 통화용: 요약 프로필 + 최근 기억 40개 + 최근 대화 요약 3개 + 최근 문서 5개. 음성이라 짧게 */
    fun forVoice(): String = buildString {
        appendLine("현재 시각(한국): ${now()}")
        val p = profiles.findById(1).orElse(null)
        if (p != null && p.briefMd.isNotBlank()) {
            appendLine()
            appendLine(p.briefMd.trim())
        }
        val remembered = memories.findAllByOrderByUpdatedAtDesc(Limit.of(VOICE_MEMORIES))
        if (remembered.isNotEmpty()) {
            appendLine()
            appendLine("기억하는 것:")
            appendMemories(remembered)
        }
        val summaries = recentSummaries(VOICE_SUMMARIES, exclude = null)
        if (summaries.isNotEmpty()) {
            appendLine()
            appendLine("최근 대화:")
            summaries.forEach { appendLine("- $it") }
        }
        appendUnread("읽지 않은 알림 (통화를 시작하면 먼저 짧게 전해):")
        appendGoals("진행 중인 목표:", withIds = true)
        appendLatestReview("최근 회고", maxChars = 400)
        appendRoutines("등록된 루틴:")
        appendActivities("최근 7일 활동:", items = 3)
        appendLine()
        appendLine(VOICE_TOOL_GUIDE)
        val docs = recentDocTitles(5)
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("최근 맡긴 문서: ${docs.joinToString(", ")}")
        }
    }
}