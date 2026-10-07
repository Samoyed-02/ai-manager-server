package com.samoyed.ai_manager_server.task

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.OffsetDateTime
import java.util.UUID
import com.samoyed.ai_manager_server.profile.ContextService

private const val MAX_ATTEMPTS = 3

private const val TASK_SYSTEM = """
너는 사용자의 개인 AI 매니저가 맡긴 문서 작업을 처리하는 작성자야.
사용자는 백엔드/서버 개발자 직무로 취업을 준비하고 지원하고 있는 대학생이며 2027년 2월 졸업 예정이야.
결과물은 바로 쓸 수 있는 완성된 마크다운 문서로 작성해. 질문으로 되묻지 말고, 정보가 부족하면 합리적으로 가정한 뒤 문서 끝에 '가정한 내용' 섹션으로 밝혀.
"""

@Component
class TaskWorker(
    private val jdbc: JdbcClient,
    private val tasks: TaskRepository,
    private val documents: DocumentRepository,
    private val client: AnthropicClient,
    private val context: ContextService,
    @Value("\${claude.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Scheduled(fixedDelay = 2000)
    fun poll() {
        val id = claimNext() ?: return
        val task = tasks.findById(id).orElseThrow()
        log.info("task {} started (attempt {})", id, task.attempts)

        try {
            val text = runClaude(task)
            documents.save(Document(task.id, extractTitle(text, task.instruction), text))
            task.status = TaskStatus.DONE
            task.error = null
            task.finishedAt = OffsetDateTime.now()
            log.info("task {} done", id)
        } catch (e: Exception) {
            log.error("task {} failed", id, e)
            task.error = e.message?.take(1000)
            if (task.attempts >= MAX_ATTEMPTS) {
                task.status = TaskStatus.FAILED
                task.finishedAt = OffsetDateTime.now()
            } else {
                task.status = TaskStatus.PENDING
            }
        }
        tasks.save(task)
    }

    /** 서버가 작업 도중 죽어서 RUNNING에 멈춘 작업을 되살린다 */
    @Scheduled(fixedDelay = 60_000)
    fun recoverStale() {
        val n = jdbc.sql(
            """
            update tasks
               set status = case when attempts >= $MAX_ATTEMPTS then 'FAILED' else 'PENDING' end
             where status = 'RUNNING'
               and started_at < now() - interval '10 minutes'
            """.trimIndent()
        ).update()
        if (n > 0) log.warn("recovered {} stale tasks", n)
    }

    private fun claimNext(): UUID? =
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
            returning id
            """.trimIndent()
        ).query(UUID::class.java).optional().orElse(null)

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
            .maxTokens(8192)
            .system(TASK_SYSTEM.trimIndent() + "\n\n" + context.forClaude())
            .addUserMessage(prompt)
            .build()

        val message = client.messages().create(params)
        return message.content()
            .mapNotNull { it.text().orElse(null)?.text() }
            .joinToString("")
    }

    private fun extractTitle(text: String, fallback: String): String {
        val first = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        val title = if (first.startsWith("# ")) first.removePrefix("# ").trim() else fallback
        return title.take(300)
    }
}