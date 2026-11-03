package com.samoyed.ai_manager_server.task

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.util.UUID
import com.samoyed.ai_manager_server.claude.ClaudeCall
import com.samoyed.ai_manager_server.claude.classifyClaudeError
import com.samoyed.ai_manager_server.claude.elapsedMs
import com.samoyed.ai_manager_server.claude.logClaudeCall
import com.samoyed.ai_manager_server.profile.ContextService

private const val MAX_ATTEMPTS = 3

/** 이 시간 넘게 RUNNING인 작업은 서버가 죽은 것으로 보고 되살린다 */
private val STALE_AFTER: Duration = Duration.ofMinutes(10)

/** Task용 Claude 호출 제한. 재시도까지 포함해도 STALE_AFTER 안에 끝나야 한다 */
private val CLAUDE_TIMEOUT: Duration = Duration.ofMinutes(4)
private const val CLAUDE_MAX_RETRIES = 1
private const val MAX_OUTPUT_TOKENS = 8192L

/** 직접 판정한 실패. message는 사용자에게 보여줘도 되는 문구라 그대로 tasks.error에 저장한다 */
private class TaskFailureException(message: String, val retryable: Boolean) : RuntimeException(message)

/** claim 시점의 attempts가 소유권 토큰 역할을 한다 */
private data class Claim(val id: UUID, val attempts: Int)

private const val TASK_SYSTEM = """
너는 사용자의 개인 AI 매니저가 맡긴 문서 작업을 처리하는 작성자야.
사용자는 백엔드/서버 개발자 직무로 취업을 준비하고 지원하고 있는 대학생이며 2027년 2월 졸업 예정이야.
결과물은 바로 쓸 수 있는 완성된 마크다운 문서로 작성해. 질문으로 되묻지 말고, 정보가 부족하면 합리적으로 가정한 뒤 문서 끝에 '가정한 내용' 섹션으로 밝혀.
"""

@Component
class TaskWorker(
    private val jdbc: JdbcClient,
    private val tasks: TaskRepository,
    private val tx: TransactionTemplate,
    private val client: AnthropicClient,
    private val context: ContextService,
    @Value("\${claude.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    init {
        require(CLAUDE_TIMEOUT.multipliedBy(CLAUDE_MAX_RETRIES + 1L) < STALE_AFTER) {
            "Claude 호출 최대 시간($CLAUDE_TIMEOUT × ${CLAUDE_MAX_RETRIES + 1})이 stale 기준($STALE_AFTER)보다 짧아야 한다"
        }
    }

    @Scheduled(fixedDelay = 2000)
    fun poll() {
        val claim = claimNext() ?: return
        val task = tasks.findById(claim.id).orElseThrow()
        log.info("task {} started (attempt {})", claim.id, claim.attempts)

        val text = try {
            runClaude(task)
        } catch (e: TaskFailureException) {
            log.warn("task {} failed (retryable={}): {}", claim.id, e.retryable, e.message)
            if (!fail(claim, e.message, e.retryable)) log.warn("task {} 소유권을 잃어 실패 기록을 버림", claim.id)
            return
        } catch (e: Exception) {
            // 원문(상태 코드, 응답 본문 등)은 로그에만 남기고 tasks.error에는 사용자용 문구만 저장한다
            val kind = classifyClaudeError(e)
            log.error("task {} failed ({})", claim.id, kind, e)
            if (!fail(claim, kind.userMessage, kind.retryable)) log.warn("task {} 소유권을 잃어 실패 기록을 버림", claim.id)
            return
        }

        if (complete(claim, extractTitle(text, task.instruction), text)) {
            log.info("task {} done", claim.id)
        } else {
            log.warn("task {} 소유권을 잃어 결과를 버림 (attempt {})", claim.id, claim.attempts)
        }
    }

    /**
     * 상태 변경과 문서 저장을 한 트랜잭션으로 처리한다.
     * 그 사이 stale 복구로 다른 워커가 작업을 다시 가져갔다면(attempts가 다름) 아무것도 쓰지 않는다.
     */
    private fun complete(claim: Claim, title: String, content: String): Boolean =
        tx.execute {
            val owned = jdbc.sql(
                """
                update tasks
                   set status = 'DONE', error = null, finished_at = now()
                 where id = :id and status = 'RUNNING' and attempts = :attempts
                """.trimIndent()
            ).param("id", claim.id).param("attempts", claim.attempts).update() == 1
            if (owned) {
                jdbc.sql(
                    """
                    insert into documents (id, task_id, title, content)
                    values (:docId, :taskId, :title, :content)
                    on conflict (task_id) do nothing
                    """.trimIndent()
                )
                    .param("docId", UUID.randomUUID())
                    .param("taskId", claim.id)
                    .param("title", title)
                    .param("content", content)
                    .update()
            }
            owned
        } == true

    /** retryable이 false면 남은 시도 횟수와 상관없이 바로 FAILED로 끝낸다 */
    private fun fail(claim: Claim, error: String?, retryable: Boolean): Boolean {
        val giveUp = !retryable || claim.attempts >= MAX_ATTEMPTS
        return jdbc.sql(
            """
            update tasks
               set error = :error,
                   status = :status,
                   finished_at = case when :giveUp then now() end
             where id = :id and status = 'RUNNING' and attempts = :attempts
            """.trimIndent()
        )
            .param("error", error)
            .param("status", if (giveUp) "FAILED" else "PENDING")
            .param("giveUp", giveUp)
            .param("id", claim.id)
            .param("attempts", claim.attempts)
            .update() == 1
    }

    /** 서버가 작업 도중 죽어서 RUNNING에 멈춘 작업을 되살린다 */
    @Scheduled(fixedDelay = 60_000)
    fun recoverStale() {
        val n = jdbc.sql(
            """
            update tasks
               set status = case when attempts >= $MAX_ATTEMPTS then 'FAILED' else 'PENDING' end,
                   finished_at = case when attempts >= $MAX_ATTEMPTS then now() end
             where status = 'RUNNING'
               and started_at < now() - :staleSeconds * interval '1 second'
            """.trimIndent()
        ).param("staleSeconds", STALE_AFTER.seconds).update()
        if (n > 0) log.warn("recovered {} stale tasks", n)
    }

    private fun claimNext(): Claim? =
        jdbc.sql(
            """
            update tasks
               set status = 'RUNNING', started_at = now(), attempts = attempts + 1
             where id = (
                   select id from tasks
                    where status = 'PENDING'
                    order by created_at
                    limit 1
                    for update skip locked
             )
            returning id, attempts
            """.trimIndent()
        ).query { rs, _ -> Claim(rs.getObject("id", UUID::class.java), rs.getInt("attempts")) }
            .optional().orElse(null)

    private fun runClaude(task: Task): String {
        val prompt = buildString {
            appendLine("다음 요청에 맞는 문서를 작성해. 첫 줄은 반드시 '# 제목' 형식이어야 해.")
            appendLine()
            appendLine("## 요청")
            appendLine(task.instruction)
            task.context?.takeIf { it.isNotBlank() }?.let {
                appendLine()
                appendLine("## 참고 맥락")
                appendLine(it)
            }
        }

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_OUTPUT_TOKENS)
            .system(TASK_SYSTEM.trimIndent() + "\n\n" + context.forClaude())
            .addUserMessage(prompt)
            .build()

        // 채팅과 공유하는 클라이언트라 Task 호출에만 타임아웃/재시도 제한을 건다
        val startedAt = System.nanoTime()
        val message = try {
            client
                .withOptions { it.timeout(CLAUDE_TIMEOUT).maxRetries(CLAUDE_MAX_RETRIES) }
                .messages()
                .create(params)
        } catch (e: Exception) {
            logClaudeCall(
                ClaudeCall(
                    kind = "task", id = task.id, model = model, stopReason = null,
                    inputTokens = null, outputTokens = null, cacheReadTokens = null, cacheCreationTokens = null,
                    durationMs = elapsedMs(startedAt), error = classifyClaudeError(e),
                )
            )
            throw e
        }
        // stop_reason 검사보다 먼저 남겨, 잘림·거절로 실패하는 호출도 기록한다
        val usage = message.usage()
        logClaudeCall(
            ClaudeCall(
                kind = "task", id = task.id, model = model, stopReason = message.stopReason().orElse(null),
                inputTokens = usage.inputTokens(), outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(null),
                cacheCreationTokens = usage.cacheCreationInputTokens().orElse(null),
                durationMs = elapsedMs(startedAt),
            )
        )
        val text = message.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("")

        // 잘렸거나 거절된 응답을 문서로 저장하지 않는다. 같은 요청은 다시 보내도 결과가 같으므로 재시도하지 않는다
        when (val reason = message.stopReason().orElse(null)) {
            StopReason.END_TURN, StopReason.STOP_SEQUENCE ->
                if (text.isBlank()) throw TaskFailureException("Claude 응답에 본문이 없음 (stop_reason=$reason)", retryable = true)
            StopReason.MAX_TOKENS -> throw TaskFailureException(
                "출력 한도($MAX_OUTPUT_TOKENS 토큰)에 도달해 문서가 잘림 (output_tokens=${message.usage().outputTokens()})",
                retryable = false,
            )
            StopReason.REFUSAL -> throw TaskFailureException(
                "Claude가 요청을 거절함 (category=${message.stopDetails().flatMap { it.category() }.map { it.toString() }.orElse("unknown")})",
                retryable = false,
            )
            else -> throw TaskFailureException("예상하지 못한 응답 종료 (stop_reason=$reason)", retryable = false)
        }
        return text
    }

    private fun extractTitle(text: String, fallback: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = if (first.startsWith("# ")) first.removePrefix("# ").trim() else fallback
        return title.take(300)
    }
}