package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.core.http.Headers
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.RateLimitException
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.claude.API_ERROR_BODY
import com.samoyed.ai_manager_server.claude.ClaudeCallLogCapture
import com.samoyed.ai_manager_server.claude.ClaudeErrorKind
import com.samoyed.ai_manager_server.task.anyArg
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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
import java.util.stream.Stream
import kotlin.streams.asStream

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatServiceTest {

    /** 다른 테스트와 같은 설정이라 Spring 컨텍스트를 공유한다 */
    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService
    @Autowired lateinit var messages: ChatMessageRepository
    @Autowired lateinit var conversations: ConversationRepository
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)
    private val events = mutableListOf<ChatEvent>()

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
        `when`(client.messages()).thenReturn(messageService)
        events.clear()
    }

    @Test
    fun `정상 응답은 COMPLETE로 저장된다`() {
        val prepared = chat.prepare(null, "안녕")
        streams(Stream.of(textDelta("안녕"), textDelta("하세요"), stop(StopReason.END_TURN)))

        chat.respond(prepared) { events += it }

        assertAll(
            { assertEquals(listOf("user" to "안녕", "assistant" to "안녕하세요"), contents(prepared.conversationId)) },
            { assertEquals(MessageStatus.COMPLETE, lastAssistant(prepared.conversationId).status) },
            { assertEquals(ChatEvent.Done(MessageStatus.COMPLETE), events.last()) },
            { assertEquals(ChatEvent.Meta(prepared.conversationId), events.first()) },
        )
    }

    @Test
    fun `출력 한도에서 잘린 응답은 TRUNCATED로 저장된다`() {
        val prepared = chat.prepare(null, "길게 설명해줘")
        streams(Stream.of(textDelta("중간까지"), stop(StopReason.MAX_TOKENS)))

        chat.respond(prepared) { events += it }

        assertAll(
            { assertEquals(MessageStatus.TRUNCATED, lastAssistant(prepared.conversationId).status) },
            { assertEquals(ChatEvent.Done(MessageStatus.TRUNCATED), events.last()) },
        )
    }

    @Test
    fun `텍스트 전에 실패하면 새 대화는 통째로 지워진다`() {
        val prepared = chat.prepare(null, "첫 질문")
        `when`(messageService.createStreaming(anyArg(MessageCreateParams::class.java)))
            .thenThrow(AnthropicIoException("connect timed out: api.anthropic.com"))

        chat.respond(prepared) { events += it }

        assertAll(
            { assertFalse(conversations.existsById(prepared.conversationId)) },
            { assertEquals(0, messages.count()) },
            { assertEquals(ChatEvent.Error("NETWORK", ClaudeErrorKind.NETWORK.userMessage), events.last()) },
        )
    }

    @Test
    fun `기존 대화에서 텍스트 전에 실패하면 이번 질문만 지워진다`() {
        val first = chat.prepare(null, "첫 질문")
        streams(Stream.of(textDelta("첫 답변"), stop(StopReason.END_TURN)))
        chat.respond(first) {}

        val second = chat.prepare(first.conversationId, "두 번째 질문")
        `when`(messageService.createStreaming(anyArg(MessageCreateParams::class.java)))
            .thenThrow(AnthropicIoException("connect timed out: api.anthropic.com"))
        chat.respond(second) { events += it }

        assertAll(
            { assertTrue(conversations.existsById(first.conversationId)) },
            { assertEquals(listOf("user" to "첫 질문", "assistant" to "첫 답변"), contents(first.conversationId)) },
        )
    }

    @Test
    fun `스트리밍 도중 실패하면 부분 응답이 INTERRUPTED로 저장된다`() {
        val prepared = chat.prepare(null, "질문")
        val partial = textDelta("부분 응답")
        streams(sequence<RawMessageStreamEvent> {
            yield(partial)
            throw IllegalStateException("스트림 끊김")
        }.asStream())

        chat.respond(prepared) { events += it }

        assertAll(
            { assertEquals("부분 응답", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.INTERRUPTED, lastAssistant(prepared.conversationId).status) },
            { assertEquals(ChatEvent.Error("UNKNOWN", ClaudeErrorKind.UNKNOWN.userMessage), events.last()) },
        )
    }

    @Test
    fun `정상 응답은 토큰 수와 히스토리 길이를 사용량 로그에 남긴다`() {
        val prepared = chat.prepare(null, "안녕")
        streams(Stream.of(messageStart(inputTokens = 1200, cacheReadTokens = 800), textDelta("안녕하세요"), stop(StopReason.END_TURN, outputTokens = 42)))

        val (line, fields) = ClaudeCallLogCapture().use { log ->
            chat.respond(prepared) { events += it }
            log.single()
        }

        assertAll(
            { assertTrue(line.startsWith("claude call kind=chat id=${prepared.conversationId} "), line) },
            { assertTrue(line.contains("stop_reason=end_turn input_tokens=1200 output_tokens=42 cache_read_tokens=800"), line) },
            { assertTrue(line.contains("history_messages=1 continuations=0 tool_calls=0 error=-"), line) },
            { assertEquals("1200", fields["input_tokens"]) },
            { assertEquals("42", fields["output_tokens"]) },
            { assertEquals("800", fields["cache_read_tokens"]) },
            { assertFalse(fields.containsKey("cache_creation_tokens")) },
        )
    }

    @Test
    fun `스트리밍 도중 실패해도 받은 만큼 사용량 로그를 남긴다`() {
        val prepared = chat.prepare(null, "질문")
        streams(sequence<RawMessageStreamEvent> {
            yield(messageStart(inputTokens = 500))
            yield(textDelta("부분"))
            throw IllegalStateException("스트림 끊김")
        }.asStream())

        val (line, _) = ClaudeCallLogCapture().use { log ->
            chat.respond(prepared) { events += it }
            log.single()
        }

        assertTrue(line.contains("stop_reason=- input_tokens=500 output_tokens=-") && line.contains("error=UNKNOWN"), line)
    }

    @Test
    fun `답변 도중 대화가 삭제되면 저장하지 않고 오류를 보낸다`() {
        val prepared = chat.prepare(null, "질문")
        streams(sequence<RawMessageStreamEvent> {
            yield(textDelta("답변"))
            // 스트리밍 중에 사용자가 대화를 지운다
            conversations.deleteReturningCount(prepared.conversationId)
            yield(stop(StopReason.END_TURN))
        }.asStream())

        chat.respond(prepared) { events += it }

        assertAll(
            { assertFalse(conversations.existsById(prepared.conversationId)) },
            { assertEquals(emptyList<Pair<String, String>>(), contents(prepared.conversationId)) },
            { assertEquals(ChatEvent.Error("CONVERSATION_DELETED", "대화가 삭제되어 답변을 저장하지 않았어요"), events.last()) },
        )
    }

    @Test
    fun `클라이언트 연결이 끊겨도 응답을 끝까지 받아 저장한다`() {
        val prepared = chat.prepare(null, "질문")
        streams(Stream.of(textDelta("끝까지 "), textDelta("받은 "), textDelta("응답"), stop(StopReason.END_TURN)))

        chat.respond(prepared) { event ->
            if (event is ChatEvent.Delta) throw IllegalStateException("ResponseBodyEmitter has already completed")
            events += event
        }

        assertAll(
            { assertEquals("끝까지 받은 응답", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.COMPLETE, lastAssistant(prepared.conversationId).status) },
            { assertEquals(listOf<ChatEvent>(ChatEvent.Meta(prepared.conversationId)), events) },
        )
    }

    @Test
    fun `텍스트 없이 거절되면 질문을 지우고 오류를 보낸다`() {
        val prepared = chat.prepare(null, "질문")
        streams(Stream.of(stop(StopReason.REFUSAL)))

        chat.respond(prepared) { events += it }

        assertAll(
            { assertFalse(conversations.existsById(prepared.conversationId)) },
            { assertEquals(ChatEvent.Error("REFUSED", "요청이 거절됐어요"), events.last()) },
        )
    }

    @Test
    fun `API 오류는 종류별 문구로 바꿔 보내고 원문은 노출하지 않는다`() {
        val prepared = chat.prepare(null, "질문")
        `when`(messageService.createStreaming(anyArg(MessageCreateParams::class.java)))
            .thenThrow(RateLimitException.builder().headers(Headers.builder().build()).body(API_ERROR_BODY).build())

        chat.respond(prepared) { events += it }

        val error = events.last() as ChatEvent.Error
        assertAll(
            { assertEquals("RATE_LIMITED", error.code) },
            { assertEquals(ClaudeErrorKind.RATE_LIMITED.userMessage, error.message) },
            { assertFalse(error.message.contains("req_secret") || error.message.contains("internal detail")) },
        )
    }

    @Test
    fun `히스토리가 40개에서 잘려 assistant로 시작하면 user 메시지부터 시작하게 한다`() {
        val conversation = conversations.save(Conversation(title = "긴 대화"))
        // user, assistant를 번갈아 40개 → 새 질문을 더하면 최근 40개는 assistant로 시작한다
        (0 until 40).forEach { i ->
            messages.save(ChatMessage(conversation.id, if (i % 2 == 0) "user" else "assistant", "메시지 $i"))
        }

        val history = chat.prepare(conversation.id, "새 질문").history

        assertAll(
            { assertEquals("user", history.first().role) },
            { assertEquals("메시지 2", history.first().content) },
            { assertEquals(39, history.size) },
            { assertEquals("새 질문", history.last().content) },
        )
    }

    private fun streams(events: Stream<RawMessageStreamEvent>) = messageService.streams(events)

    private fun contents(conversationId: UUID): List<Pair<String, String>> =
        messages.findByConversationIdOrderByIdAsc(conversationId).map { it.role to it.content }

    private fun lastAssistant(conversationId: UUID): ChatMessage =
        messages.findByConversationIdOrderByIdAsc(conversationId).last { it.role == "assistant" }
}
