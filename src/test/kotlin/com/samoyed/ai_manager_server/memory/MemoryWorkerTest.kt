package com.samoyed.ai_manager_server.memory

import com.anthropic.client.AnthropicClient
import com.anthropic.errors.AnthropicIoException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.StopReason
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.chat.ChatMessage
import com.samoyed.ai_manager_server.chat.ChatMessageRepository
import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import com.samoyed.ai_manager_server.task.anyArg
import com.samoyed.ai_manager_server.task.claudeResponse
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
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
import java.util.UUID

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MemoryWorkerTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var worker: MemoryWorker
    @Autowired lateinit var conversations: ConversationRepository
    @Autowired lateinit var messages: ChatMessageRepository
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val sent = mutableListOf<MessageCreateParams>()

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from memories").update()
        jdbc.sql("delete from conversations").update()
        `when`(client.messages()).thenReturn(messageService)
        sent.clear()
    }

    @Test
    fun `통화는 저장되자마자 기억과 요약을 남긴다`() {
        val call = conversation(ConversationChannel.VOICE, "user" to "매일 아침 7시에 일어나서 알고리즘 공부해")
        replies(extraction("아침 루틴을 이야기함.", add("ROUTINE", "사용자는 매일 아침 7시에 일어나 알고리즘을 공부한다")))

        worker.poll()

        val memory = memories.findAll().single()
        assertAll(
            { assertEquals(MemoryCategory.ROUTINE, memory.category) },
            { assertEquals(call, memory.sourceConversationId) },
            { assertEquals("아침 루틴을 이야기함.", summary(call)) },
            { assertNotNull(processedAt(call)) },
            { assertEquals(1, sent.size) },
        )
        // 요청은 JSON 스키마 형식으로 받고, 대화 원문을 넣는다
        val request = sent.single()
        assertAll(
            { assertTrue(request.outputConfig().get().format().isPresent) },
            { assertTrue(request.messages().single().content().asString().contains("사용자: 매일 아침 7시에 일어나서 알고리즘 공부해")) },
        )
    }

    @Test
    fun `채팅은 30분 동안 조용해야 처리한다`() {
        val chat = conversation(ConversationChannel.CHAT, "user" to "정보처리기사 준비 중이야")
        replies(extraction("자격증 준비.", add("GOAL", "사용자는 정보처리기사를 준비한다")))

        worker.poll()
        assertEquals(0, sent.size)

        idleFor(chat, minutes = 31)
        worker.poll()
        assertEquals(1, sent.size)
        assertEquals(1, memories.count())
    }

    @Test
    fun `한 번 처리한 대화는 새 메시지가 올 때만 다시 처리한다`() {
        val call = conversation(ConversationChannel.VOICE, "user" to "토익 800점이 목표야")
        replies(
            extraction("토익 목표.", add("GOAL", "사용자는 토익 800점이 목표다")),
            extraction("토익 점수 갱신.", add("PROGRESS", "사용자는 토익 850점을 받았다")),
        )

        worker.poll()
        worker.poll()
        assertEquals(1, sent.size)

        messages.save(ChatMessage(call, "user", "토익 850점 받았어"))
        jdbc.sql("update conversations set updated_at = now() where id = :id").param("id", call).update()
        worker.poll()

        assertAll(
            { assertEquals(2, sent.size) },
            // 두 번째 요청에는 이미 저장된 기억을 넣어 중복 대신 수정하게 한다
            { assertTrue(sent[1].messages().single().content().asString().contains("사용자는 토익 800점이 목표다")) },
            { assertEquals("토익 점수 갱신.", summary(call)) },
        )
    }

    @Test
    fun `기존 기억을 고치고 지우며, 잘못된 항목과 민감 정보는 버린다`() {
        val old = memories.save(Memory(MemoryCategory.GOAL, "사용자는 토익 800점이 목표다"))
        val stale = memories.save(Memory(MemoryCategory.ROUTINE, "사용자는 밤 12시에 잔다"))
        val call = conversation(ConversationChannel.VOICE, "user" to "토익 목표 900으로 올렸어. 요즘은 11시에 자")
        replies(
            extraction(
                "목표 상향과 수면 시간 변경.",
                op("update", old.id.toString(), "GOAL", "사용자는 토익 900점이 목표다"),
                op("delete", stale.id.toString(), "ROUTINE", ""),
                add("ROUTINE", "사용자는 밤 11시에 잔다"),
                add("FACT", "사용자의 카드번호는 4111 1111 1111 1111이다"),
                op("update", UUID.randomUUID().toString(), "GOAL", "없는 기억"),
                add("UNKNOWN", "잘못된 카테고리"),
            )
        )

        worker.poll()

        assertAll(
            { assertEquals("사용자는 토익 900점이 목표다", memories.findById(old.id).get().content) },
            { assertTrue(memories.findById(stale.id).isEmpty) },
            { assertEquals(setOf("사용자는 토익 900점이 목표다", "사용자는 밤 11시에 잔다"), memories.findAll().map { it.content }.toSet()) },
            { assertNotNull(processedAt(call)) },
        )
    }

    @Test
    fun `요약에 민감 정보가 있으면 요약은 저장하지 않는다`() {
        val call = conversation(ConversationChannel.VOICE, "user" to "와이파이 비번 알려줄게")
        replies(extraction("와이파이 비번은 qwer1234라고 함."))

        worker.poll()

        assertAll(
            { assertNull(summary(call)) },
            { assertNotNull(processedAt(call)) },
        )
    }

    @Test
    fun `실패하면 처리하지 않은 채로 두고 바로 다시 시도하지 않는다`() {
        val call = conversation(ConversationChannel.VOICE, "user" to "안녕")
        `when`(messageService.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            sent += it.getArgument<MessageCreateParams>(0)
            throw AnthropicIoException("connect timed out")
        }

        worker.poll()
        worker.poll()

        assertAll(
            // claim이 남아 있어 CLAIM_LEASE가 지나기 전에는 다시 가져가지 않는다
            { assertEquals(1, sent.size) },
            { assertNull(processedAt(call)) },
            { assertEquals(0, memories.count()) },
        )
    }

    // --- 도우미 ---

    private fun conversation(channel: ConversationChannel, vararg turns: Pair<String, String>): UUID {
        val conversation = conversations.save(Conversation(title = "테스트", channel = channel))
        turns.forEach { (role, text) -> messages.save(ChatMessage(conversation.id, role, text)) }
        return conversation.id
    }

    private fun idleFor(id: UUID, minutes: Int) {
        jdbc.sql("update conversations set updated_at = now() - :m * interval '1 minute' where id = :id")
            .param("m", minutes).param("id", id).update()
    }

    private fun replies(vararg json: String) {
        val responses = json.map { claudeResponse(text = it, stopReason = StopReason.END_TURN) }
        `when`(messageService.create(anyArg(MessageCreateParams::class.java))).thenAnswer {
            sent += it.getArgument<MessageCreateParams>(0)
            responses[(sent.size - 1).coerceAtMost(responses.size - 1)]
        }
    }

    private fun extraction(summary: String, vararg ops: String) =
        """{"summary":"$summary","operations":[${ops.joinToString(",")}]}"""

    private fun add(category: String, content: String) = op("add", "", category, content)

    private fun op(op: String, id: String, category: String, content: String) =
        """{"op":"$op","id":"$id","category":"$category","content":"$content"}"""

    private fun summary(id: UUID): String? =
        jdbc.sql("select summary from conversations where id = :id").param("id", id)
            .query { rs, _ -> rs.getString(1) }.list().single()

    private fun processedAt(id: UUID): Any? =
        jdbc.sql("select memory_processed_at from conversations where id = :id").param("id", id)
            .query { rs, _ -> rs.getObject(1) }.list().single()
}
