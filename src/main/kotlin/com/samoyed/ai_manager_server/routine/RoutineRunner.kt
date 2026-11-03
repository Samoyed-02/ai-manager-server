package com.samoyed.ai_manager_server.routine

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.samoyed.ai_manager_server.claude.ClaudeCall
import com.samoyed.ai_manager_server.claude.classifyClaudeError
import com.samoyed.ai_manager_server.claude.elapsedMs
import com.samoyed.ai_manager_server.claude.logClaudeCall
import com.samoyed.ai_manager_server.profile.ContextService
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** 실행 중 실패하거나 서버가 죽으면 이 시간 뒤 다시 가져간다. 일시적인 실패는 이 간격으로 재시도된다 */
private val CLAIM_LEASE: Duration = Duration.ofMinutes(10)
private const val BATCH = 10
private val CLAUDE_TIMEOUT: Duration = Duration.ofMinutes(2)

private const val BRIEFING_SYSTEM = """
너는 사용자의 개인 AI 매니저야. 아래 사용자 정보(프로필, 기억, 루틴, 활동 기록, 맡긴 문서)를 보고, 사용자가 등록한 지시에 맞는 짧은 브리핑을 써.
한국어로, 5줄 이내, 바로 행동할 수 있게 구체적으로. 인사말이나 군더더기는 빼.
정보가 부족하면 아는 범위에서 쓰고, 지어내지 마.
"""

/** 시간이 된 루틴을 실행해 알림함에 남긴다. 결과는 다음 채팅·통화의 맥락으로 사용자에게 전해진다 */
@Component
class RoutineRunner(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val routines: RoutineRepository,
    private val notifications: NotificationRepository,
    private val client: AnthropicClient,
    private val context: ContextService,
    @Value("\${claude.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 60_000)
    fun poll() {
        repeat(BATCH) {
            val id = claimNext() ?: return
            run(id)
        }
    }

    private fun run(id: UUID) {
        val routine = routines.findById(id).orElse(null) ?: return
        val body = try {
            when (routine.kind) {
                RoutineKind.REMINDER -> routine.message
                RoutineKind.BRIEFING -> briefing(routine)
            }
        } catch (e: Exception) {
            val kind = classifyClaudeError(e)
            log.error("routine {} failed ({})", id, kind, e)
            // 일시적인 오류면 claim을 남겨 CLAIM_LEASE 뒤 다시 시도한다. 다시 해도 같은 오류면 실패를 알리고 다음 실행으로 넘어간다
            if (kind.retryable) return
            "브리핑을 만들지 못했어요. ${kind.userMessage}"
        }
        complete(routine, body)
    }

    private fun briefing(routine: Routine): String {
        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(1024)
            .system(BRIEFING_SYSTEM.trimIndent() + "\n\n" + context.forClaude())
            .addUserMessage("루틴 '${routine.title}'의 지시: ${routine.message}")
            .build()
        val startedAt = System.nanoTime()
        val message = try {
            client.withOptions { it.timeout(CLAUDE_TIMEOUT).maxRetries(1) }.messages().create(params)
        } catch (e: Exception) {
            logClaudeCall(
                ClaudeCall(
                    kind = "routine", id = routine.id, model = model, stopReason = null,
                    inputTokens = null, outputTokens = null, cacheReadTokens = null, cacheCreationTokens = null,
                    durationMs = elapsedMs(startedAt), error = classifyClaudeError(e),
                )
            )
            throw e
        }
        val usage = message.usage()
        val stopReason = message.stopReason().orElse(null)
        logClaudeCall(
            ClaudeCall(
                kind = "routine", id = routine.id, model = model, stopReason = stopReason,
                inputTokens = usage.inputTokens(), outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(null),
                cacheCreationTokens = usage.cacheCreationInputTokens().orElse(null),
                durationMs = elapsedMs(startedAt),
            )
        )
        val text = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("").trim()
        return when {
            stopReason == StopReason.REFUSAL -> "브리핑 요청이 거절됐어요. 루틴 내용을 바꿔 주세요."
            text.isEmpty() -> "브리핑이 비어 있어요."
            else -> text
        }
    }

    /**
     * 알림을 남기고 다음 실행 시각으로 넘긴다. 서버가 꺼져 있어 여러 번 밀렸어도 한 번만 실행하고 지금 이후로 넘긴다.
     * 실행 중 루틴이 삭제됐으면 아무것도 쓰지 않고, 수정됐으면 바뀐 요일·시각으로 다음 실행을 잡는다
     */
    private fun complete(routine: Routine, body: String) {
        var next: OffsetDateTime? = null
        tx.executeWithoutResult {
            val fresh = routines.findById(routine.id).orElse(null) ?: return@executeWithoutResult
            next = RoutineSchedule.nextRun(fresh.dayOfWeeks, fresh.timeOfDay, OffsetDateTime.now())
            val owned = jdbc.sql(
                """
                update routines
                   set next_run_at = :next, last_run_at = now(), claimed_at = null
                 where id = :id and claimed_at is not null
                """.trimIndent()
            ).param("next", next).param("id", routine.id).update() == 1
            if (owned) notifications.save(Notification(routine.id, fresh.title, body))
        }
        log.info("routine {} ran ({}), next {}", routine.id, routine.kind, next ?: "deleted")
    }

    private fun claimNext(): UUID? =
        jdbc.sql(
            """
            update routines
               set claimed_at = now()
             where id = (
                   select id from routines
                    where enabled
                      and next_run_at <= now()
                      and (claimed_at is null or claimed_at < now() - :leaseSeconds * interval '1 second')
                    order by next_run_at
                    limit 1
                    for update skip locked
             )
            returning id
            """.trimIndent()
        ).param("leaseSeconds", CLAIM_LEASE.seconds)
            .query(UUID::class.java).optional().orElse(null)
}
