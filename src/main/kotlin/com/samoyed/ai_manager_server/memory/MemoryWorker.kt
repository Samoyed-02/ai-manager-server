package com.samoyed.ai_manager_server.memory

import com.anthropic.client.AnthropicClient
import com.anthropic.core.JsonValue
import com.anthropic.core.jsonMapper
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.StopReason
import com.fasterxml.jackson.databind.JsonNode
import com.samoyed.ai_manager_server.chat.ChatMessageRepository
import com.samoyed.ai_manager_server.claude.ClaudeCall
import com.samoyed.ai_manager_server.claude.classifyClaudeError
import com.samoyed.ai_manager_server.claude.elapsedMs
import com.samoyed.ai_manager_server.claude.logClaudeCall
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.domain.Limit
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import java.time.Duration
import java.time.OffsetDateTime
import java.util.UUID

/** 채팅은 마지막 메시지 후 이만큼 조용해야 끝난 것으로 보고 기억을 뽑는다. 통화는 저장되면 바로 */
private val CHAT_IDLE: Duration = Duration.ofMinutes(30)

/** 추출 중 실패하거나 서버가 죽으면 이 시간 뒤 다시 가져간다. 실패가 반복돼도 이 간격으로만 재시도한다 */
private val CLAIM_LEASE: Duration = Duration.ofMinutes(10)

/** 한 번 실행에 처리할 대화 수. 배포 직후 기존 대화가 한꺼번에 몰리지 않게 한다 */
private const val BATCH = 5

private val CLAUDE_TIMEOUT: Duration = Duration.ofMinutes(2)

/** 프롬프트에 넣을 대화 원문의 최대 글자 수. 넘으면 앞부분을 버린다 */
private const val MAX_TRANSCRIPT_CHARS = 60_000

/** 프롬프트에 넣을 기존 기억 수. 맥락에 넣는 수(ContextService)와 같다 */
const val MAX_MEMORIES_IN_CONTEXT = 200

private const val MEMORY_SYSTEM = """
너는 사용자의 개인 AI 매니저에서 기억을 관리하는 담당이야. 대화를 읽고, 앞으로의 대화에서 사용자를 더 잘 돕는 데 쓸 기억을 정리해.

기억할 것: 사용자에 대해 오래 유효한 정보
- FACT: 사실 (학교, 전공, 직무 목표, 쓰는 기술 등)
- PREFERENCE: 선호 (답변 스타일, 좋아하는 것·싫어하는 것)
- ROUTINE: 루틴 (매일·매주 하는 일, 시간대)
- GOAL: 목표 (기한이 있으면 포함)
- PROGRESS: 진행 상황 (목표 대비 지금 어디까지 왔는지)
- OTHER: 그 밖에 오래 쓸모 있는 정보

기억하지 말 것
- 일회성 잡담, 그날의 기분, AI가 한 말이나 제안
- 민감 정보: 비밀번호, 인증 코드, API 키·토큰, 카드·계좌번호, 주민등록번호, 여권번호, 정확한 집 주소, 전화번호, 다른 사람의 개인정보

규칙
- 기존 기억과 같은 내용이면 추가하지 마. 내용이 바뀌었으면 update, 더 이상 맞지 않으면 delete.
- 기억 하나는 한 문장, 200자 이내 한국어. 사용자는 '사용자'로 지칭해.
- 바꿀 것이 없으면 operations는 빈 배열.
- summary는 이 대화의 요점을 2~3문장 한국어로. 민감 정보는 빼.
"""

/** 대화마다 요약 + 기억 변경 목록을 정해진 JSON으로 받는다 */
private val OUTPUT_SCHEMA: Map<String, Any> = mapOf(
    "type" to "object",
    "additionalProperties" to false,
    "required" to listOf("summary", "operations"),
    "properties" to mapOf(
        "summary" to mapOf("type" to "string"),
        "operations" to mapOf(
            "type" to "array",
            "items" to mapOf(
                "type" to "object",
                "additionalProperties" to false,
                "required" to listOf("op", "id", "category", "content"),
                "properties" to mapOf(
                    "op" to mapOf("type" to "string", "enum" to listOf("add", "update", "delete")),
                    "id" to mapOf("type" to "string", "description" to "update·delete할 기존 기억의 id. add면 빈 문자열"),
                    "category" to mapOf("type" to "string", "enum" to MemoryCategory.entries.map { it.name }),
                    "content" to mapOf("type" to "string", "description" to "기억 내용. delete면 빈 문자열"),
                ),
            ),
        ),
    ),
)

/**
 * 끝난 대화에서 사용자에 대한 기억을 뽑아 memories에 반영하고, 대화 요약을 남긴다.
 * 대화가 이어지면(updated_at이 커지면) 다시 대상이 되고, 그때는 기존 기억을 보고 고친다
 */
@Component
class MemoryWorker(
    private val jdbc: JdbcClient,
    private val tx: TransactionTemplate,
    private val client: AnthropicClient,
    private val messages: ChatMessageRepository,
    private val memories: MemoryRepository,
    @Value("\${claude.model}") private val model: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private data class Claim(val id: UUID, val updatedAt: OffsetDateTime)

    data class Operation(val op: String, val id: String, val category: String, val content: String)
    data class Extraction(val summary: String, val operations: List<Operation>)

    @Scheduled(fixedDelay = 60_000)
    fun poll() {
        repeat(BATCH) {
            val claim = claimNext() ?: return
            process(claim)
        }
    }

    private fun process(claim: Claim) {
        val extraction = try {
            extract(claim.id) ?: return
        } catch (e: Exception) {
            // claim은 그대로 둔다. CLAIM_LEASE가 지나면 다시 가져가므로 그 간격으로만 재시도된다
            log.error("conversation {} memory extraction failed ({})", claim.id, classifyClaudeError(e), e)
            return
        }
        try {
            apply(claim, extraction)
        } catch (e: Exception) {
            // 추출 중 대화가 삭제되면 외래 키 오류로 여기 온다
            log.warn("conversation {} memory update skipped: {}", claim.id, e.toString())
        }
    }

    /** 처리할 대화가 없거나 메시지가 없으면 null */
    private fun extract(conversationId: UUID): Extraction? {
        val transcript = messages.findByConversationIdOrderByIdAsc(conversationId)
            .joinToString("\n") { (if (it.role == "user") "사용자: " else "AI: ") + it.content }
            .takeLast(MAX_TRANSCRIPT_CHARS)
        if (transcript.isBlank()) {
            markProcessedWithoutChanges(conversationId)
            return null
        }
        val existing = memories.findAllByOrderByUpdatedAtDesc(Limit.of(MAX_MEMORIES_IN_CONTEXT))
            .joinToString("\n") { "- id=${it.id} [${it.category.name}] ${it.content}" }
            .ifBlank { "(없음)" }

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(4096)
            .system(MEMORY_SYSTEM.trimIndent())
            .addUserMessage("## 기존 기억\n$existing\n\n## 대화\n$transcript")
            .outputConfig(
                OutputConfig.builder()
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonOutputFormat.Schema.builder().putAllAdditionalProperties(OUTPUT_SCHEMA.mapValues { JsonValue.from(it.value) }).build())
                            .build()
                    )
                    .build()
            )
            .build()

        val startedAt = System.nanoTime()
        val message = try {
            client.withOptions { it.timeout(CLAUDE_TIMEOUT).maxRetries(1) }.messages().create(params)
        } catch (e: Exception) {
            logClaudeCall(
                ClaudeCall(
                    kind = "memory", id = conversationId, model = model, stopReason = null,
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
                kind = "memory", id = conversationId, model = model, stopReason = stopReason,
                inputTokens = usage.inputTokens(), outputTokens = usage.outputTokens(),
                cacheReadTokens = usage.cacheReadInputTokens().orElse(null),
                cacheCreationTokens = usage.cacheCreationInputTokens().orElse(null),
                durationMs = elapsedMs(startedAt),
            )
        )
        check(stopReason == StopReason.END_TURN) { "unexpected stop_reason $stopReason" }
        val json = message.content().mapNotNull { it.text().orElse(null)?.text() }.joinToString("")
        return parse(json)
    }

    private fun parse(json: String): Extraction {
        val root: JsonNode = jsonMapper().readTree(json)
        return Extraction(
            summary = root.path("summary").asText(""),
            operations = root.path("operations").map {
                Operation(it.path("op").asText(""), it.path("id").asText(""), it.path("category").asText(""), it.path("content").asText(""))
            },
        )
    }

    /** 기억 변경과 요약 저장을 한 트랜잭션으로. 민감 정보나 잘못된 항목은 버린다 */
    private fun apply(claim: Claim, extraction: Extraction) {
        var applied = 0
        var dropped = 0
        tx.executeWithoutResult {
            for (op in extraction.operations) {
                if (applyOne(op, claim.id)) applied++ else dropped++
            }
            val summary = extraction.summary.trim().takeIf { it.isNotEmpty() && !SensitiveInfoFilter.isSensitive(it) }
            jdbc.sql(
                """
                update conversations
                   set summary = coalesce(cast(:summary as text), summary),
                       memory_processed_at = :seen,
                       memory_claimed_at = null
                 where id = :id
                """.trimIndent()
            ).param("summary", summary).param("seen", claim.updatedAt).param("id", claim.id).update()
        }
        log.info("conversation {} memories updated (applied={}, dropped={})", claim.id, applied, dropped)
    }

    private fun applyOne(op: Operation, conversationId: UUID): Boolean {
        val category = MemoryCategory.entries.firstOrNull { it.name == op.category }
        val content = op.content.trim()
        val target = runCatching { UUID.fromString(op.id) }.getOrNull()?.let { memories.findById(it).orElse(null) }
        fun valid() = category != null && content.isNotEmpty() && content.length <= MAX_MEMORY_CONTENT &&
            !SensitiveInfoFilter.isSensitive(content)
        return when (op.op) {
            "add" -> valid().also { if (it) memories.save(Memory(category!!, content, conversationId)) }
            "update" -> (target != null && valid()).also {
                if (it) {
                    target!!.category = category!!
                    target.content = content
                    target.updatedAt = OffsetDateTime.now()
                    memories.save(target)
                }
            }
            "delete" -> (target != null).also { if (it) memories.delete(target!!) }
            else -> false
        }
    }

    private fun markProcessedWithoutChanges(conversationId: UUID) {
        jdbc.sql(
            "update conversations set memory_processed_at = updated_at, memory_claimed_at = null where id = :id"
        ).param("id", conversationId).update()
    }

    /**
     * 기억을 뽑을 대화 하나를 가져간다. 통화는 바로, 채팅은 CHAT_IDLE 동안 조용하면 대상이다.
     * 여러 서버가 동시에 돌아도 SKIP LOCKED와 claim 표시로 같은 대화를 두 번 처리하지 않는다
     */
    private fun claimNext(): Claim? =
        jdbc.sql(
            """
            update conversations
               set memory_claimed_at = now()
             where id = (
                   select id from conversations
                    where (memory_processed_at is null or memory_processed_at < updated_at)
                      and (memory_claimed_at is null or memory_claimed_at < now() - :leaseSeconds * interval '1 second')
                      and (channel = 'VOICE' or updated_at < now() - :idleSeconds * interval '1 second')
                    order by updated_at
                    limit 1
                    for update skip locked
             )
            returning id, updated_at
            """.trimIndent()
        )
            .param("leaseSeconds", CLAIM_LEASE.seconds)
            .param("idleSeconds", CHAT_IDLE.seconds)
            .query { rs, _ -> Claim(rs.getObject("id", UUID::class.java), rs.getObject("updated_at", OffsetDateTime::class.java)) }
            .optional().orElse(null)
}
