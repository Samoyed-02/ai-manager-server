package com.samoyed.ai_manager_server.profile

import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import com.samoyed.ai_manager_server.memory.MAX_MEMORIES_IN_CONTEXT
import com.samoyed.ai_manager_server.memory.Memory
import com.samoyed.ai_manager_server.memory.MemoryCategory
import com.samoyed.ai_manager_server.memory.MemoryRepository
import com.samoyed.ai_manager_server.task.DocumentRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID

private const val CHAT_SUMMARIES = 5
private const val VOICE_MEMORIES = 40
private const val VOICE_SUMMARIES = 3

@Service
class ContextService(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
    private val memories: MemoryRepository,
    private val conversations: ConversationRepository,
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
        val docs = recentDocTitles(5)
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("최근 맡긴 문서: ${docs.joinToString(", ")}")
        }
    }
}