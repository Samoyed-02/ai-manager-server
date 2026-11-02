package com.samoyed.ai_manager_server.profile

import com.samoyed.ai_manager_server.task.DocumentRepository
import org.springframework.data.domain.Limit
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@Service
class ContextService(
    private val profiles: ProfileRepository,
    private val documents: DocumentRepository,
) {
    private val zone = ZoneId.of("Asia/Seoul")
    private val fmt = DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E) HH:mm", Locale.KOREAN)

    private fun now() = ZonedDateTime.now(zone).format(fmt)

    private fun recentDocTitles(n: Int) =
        documents.findAllByOrderByCreatedAtDesc(Limit.of(n)).map { it.title }

    /** Claude용: 전체 프로필 + 최근 문서 10개 */
    fun forClaude(): String = buildString {
        appendLine("## 현재 시각 (한국)")
        appendLine(now())
        val p = profiles.findById(1).orElse(null)
        if (p != null && p.fullMd.isNotBlank()) {
            appendLine()
            appendLine(p.fullMd.trim())
        }
        val docs = recentDocTitles(10)
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("## 최근 맡겨서 작성된 문서")
            docs.forEach { appendLine("- $it") }
        }
    }

    /** Gemini 통화용: 요약 프로필 + 최근 문서 5개 */
    fun forVoice(): String = buildString {
        appendLine("현재 시각(한국): ${now()}")
        val p = profiles.findById(1).orElse(null)
        if (p != null && p.briefMd.isNotBlank()) {
            appendLine()
            appendLine(p.briefMd.trim())
        }
        val docs = recentDocTitles(5)
        if (docs.isNotEmpty()) {
            appendLine()
            appendLine("최근 맡긴 문서: ${docs.joinToString(", ")}")
        }
    }
}

data class ContextDto(val text: String)

@RestController
class ContextController(private val context: ContextService) {
    @GetMapping("/context/voice")
    fun voice() = ContextDto(context.forVoice())
}