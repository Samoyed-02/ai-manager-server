package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageParam
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.claude.ClaudeCallLogCapture
import org.junit.jupiter.api.Assertions.assertEquals
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

/** 채팅의 웹 검색·페이지 읽기. 스트림은 실제 API 모양의 JSON 이벤트로 만든다 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatWebToolsTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService
    @Autowired lateinit var messages: ChatMessageRepository
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
    fun `요청에 웹 검색과 페이지 읽기 도구가 횟수 제한과 함께 들어간다`() {
        val sent = messageService.streamsInOrder(chatStream { start(); text("답"); end("end_turn") })

        chat.respond(chat.prepare(null, "질문")) { events += it }

        val tools = sent.single().tools().get()
        val search = tools.single { it.isWebSearchTool20260209() }.asWebSearchTool20260209()
        val fetch = tools.single { it.isWebFetchTool20260209() }.asWebFetchTool20260209()
        assertAll(
            // 웹 도구 2개 + 서버 도구 7개
            { assertEquals(9, tools.size) },
            { assertEquals(3L, search.maxUses().get()) },
            { assertEquals("KR", search.userLocation().get().country().get()) },
            { assertEquals(3L, fetch.maxUses().get()) },
            { assertEquals(20_000L, fetch.maxContentTokens().get()) },
        )
    }

    @Test
    fun `검색 인용과 읽은 페이지가 답변 끝에 출처로 붙고 중복은 한 번만 나온다`() {
        val prepared = chat.prepare(null, "요즘 소식 알려줘")
        messageService.streamsInOrder(chatStream {
            start()
            webSearch("소식")
            text("첫째 소식.", citation = "https://news.example.com/a" to "기사 [A]")
            text(" 같은 기사.", citation = "https://news.example.com/a" to "기사 [A]")
            webFetch("https://blog.example.com/b", "블로그 B")
            text(" 블로그 내용.")
            end("end_turn", searches = 1, fetches = 1)
        })

        val (line, _) = ClaudeCallLogCapture().use { log ->
            chat.respond(prepared) { events += it }
            log.single()
        }

        val expectedFooter = "\n\n**출처**\n- [기사 (A)](https://news.example.com/a)\n- [블로그 B](https://blog.example.com/b)"
        assertAll(
            { assertEquals("첫째 소식. 같은 기사. 블로그 내용.$expectedFooter", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.COMPLETE, lastAssistant(prepared.conversationId).status) },
            { assertEquals(ChatEvent.Delta(expectedFooter), events[events.size - 2]) },
            { assertEquals(ChatEvent.Done(MessageStatus.COMPLETE), events.last()) },
            { assertTrue(line.contains("web_search_requests=1 web_fetch_requests=1 continuations=0"), line) },
        )
    }

    @Test
    fun `검색하지 않은 답변에는 출처를 붙이지 않는다`() {
        val prepared = chat.prepare(null, "안녕")
        messageService.streamsInOrder(chatStream { start(); text("안녕하세요"); end("end_turn") })

        chat.respond(prepared) { events += it }

        assertEquals("안녕하세요", lastAssistant(prepared.conversationId).content)
    }

    @Test
    fun `pause_turn이면 받은 응답을 그대로 붙여 이어 받는다`() {
        val prepared = chat.prepare(null, "자세히 조사해줘")
        val sent = messageService.streamsInOrder(
            chatStream { start(inputTokens = 100); text("앞부분."); webSearch("조사"); end("pause_turn", outputTokens = 20, searches = 1) },
            chatStream { start(inputTokens = 300); text(" 뒷부분."); end("end_turn", outputTokens = 30, searches = 1) },
        )

        val (line, _) = ClaudeCallLogCapture().use { log ->
            chat.respond(prepared) { events += it }
            log.single()
        }

        assertEquals(2, sent.size)
        val (first, second) = sent
        val resent = second.messages().last()
        assertAll(
            { assertEquals("앞부분. 뒷부분.", lastAssistant(prepared.conversationId).content) },
            { assertEquals(MessageStatus.COMPLETE, lastAssistant(prepared.conversationId).status) },
            // 이어 보낼 때는 멈춘 응답(검색 블록 포함)을 assistant 메시지로 그대로 붙인다
            { assertEquals(first.messages().size + 1, second.messages().size) },
            { assertEquals(MessageParam.Role.ASSISTANT, resent.role()) },
            { assertTrue(resent.content().asBlockParams().any { it.isServerToolUse() }) },
            { assertTrue(resent.content().asBlockParams().any { it.isWebSearchToolResult() }) },
            { assertTrue(line.contains("input_tokens=400 output_tokens=50"), line) },
            { assertTrue(line.contains("stop_reason=end_turn"), line) },
            { assertTrue(line.contains("web_search_requests=2 web_fetch_requests=0 continuations=1"), line) },
        )
    }

    @Test
    fun `이어 받기 한도를 넘기면 받은 만큼 TRUNCATED로 저장한다`() {
        val prepared = chat.prepare(null, "끝없이 조사해줘")
        val paused = { n: Int -> chatStream { start(); text("$n."); webSearch("q$n"); end("pause_turn") } }
        val sent = messageService.streamsInOrder(paused(1), paused(2), paused(3), paused(4))

        chat.respond(prepared) { events += it }

        assertAll(
            // 첫 요청 + 이어 받기 3번
            { assertEquals(4, sent.size) },
            { assertEquals("1.2.3.4.", lastAssistant(prepared.conversationId).content.substringBefore("\n\n**출처**")) },
            { assertEquals(MessageStatus.TRUNCATED, lastAssistant(prepared.conversationId).status) },
            { assertEquals(ChatEvent.Done(MessageStatus.TRUNCATED), events.last()) },
        )
    }

    private fun lastAssistant(conversationId: UUID): ChatMessage =
        messages.findByConversationIdOrderByIdAsc(conversationId).last { it.role == "assistant" }
}
