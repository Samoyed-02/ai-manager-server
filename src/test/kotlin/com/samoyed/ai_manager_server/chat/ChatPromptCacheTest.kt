package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageParam
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
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

/** 메시지 텍스트. 문자열이든 블록이든 텍스트만 이어 붙인다 (캐시 지점 표시는 무시) */
private fun MessageParam.text(): String =
    content().let { c -> if (c.isString()) c.asString() else c.asBlockParams().mapNotNull { it.text().orElse(null)?.text() }.joinToString("") }

private fun MessageParam.cached(): Boolean =
    content().let { c -> c.isBlockParams() && c.asBlockParams().any { b -> b.text().flatMap { it.cacheControl() }.isPresent } }

/** 프롬프트 캐시: 바뀌지 않는 앞부분에 캐시 지점을 두고, 매 턴 바뀌는 맥락은 대화 기록 뒤로 보낸다 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class ChatPromptCacheTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
        `when`(client.messages()).thenReturn(messageService)
    }

    @Test
    fun `고정 지시와 현재 사용자 메시지에 캐시 지점을 두고 맥락은 그 뒤 system 메시지로 보낸다`() {
        val prepared = chat.prepare(null, "안녕")
        val sent = messageService.streamsInOrder(chatStream { start(); text("안녕하세요"); end("end_turn") })
        chat.respond(prepared) { }

        val params = sent.single()
        val system = params.system().get().asTextBlockParams()
        val messages = params.messages()
        assertAll(
            // 시스템에는 고정 지시만 있고, 앞의 도구 정의와 함께 캐시된다
            { assertEquals(1, system.size) },
            { assertTrue(system.single().cacheControl().isPresent) },
            { assertFalse(system.single().text().contains("현재 시각")) },
            // 같은 턴의 도구 라운드용 자동 캐시 지점
            { assertTrue(params.cacheControl().isPresent) },
            { assertEquals(listOf(MessageParam.Role.USER, MessageParam.Role.SYSTEM), messages.map { it.role() }) },
            { assertEquals("안녕", messages[0].text()) },
            { assertTrue(messages[0].cached()) },
            { assertTrue(messages[1].text().contains("## 현재 시각 (한국)")) },
        )
    }

    @Test
    fun `다음 턴 요청은 이전 요청의 캐시 지점까지 앞부분이 같다`() {
        val first = chat.prepare(null, "첫 질문")
        val firstRequests = messageService.streamsInOrder(chatStream { start(); text("첫 답"); end("end_turn") })
        chat.respond(first) { }
        val firstSent = firstRequests.single()

        val second = chat.prepare(first.conversationId, "둘째 질문")
        val secondRequests = messageService.streamsInOrder(chatStream { start(); text("둘째 답"); end("end_turn") })
        chat.respond(second) { }
        val secondSent = secondRequests.single()

        val cachedUpTo = firstSent.messages().indexOfFirst { it.cached() }
        assertAll(
            { assertEquals(firstSent.system(), secondSent.system()) },
            { assertEquals(firstSent.tools(), secondSent.tools()) },
            {
                assertEquals(
                    firstSent.messages().take(cachedUpTo + 1).map { it.role() to it.text() },
                    secondSent.messages().take(cachedUpTo + 1).map { it.role() to it.text() },
                )
            },
            // 이번 턴: [첫 질문, 첫 답, 둘째 질문(캐시 지점), 맥락]
            { assertEquals(listOf("첫 질문", "첫 답", "둘째 질문"), secondSent.messages().take(3).map { it.text() }) },
            { assertEquals(listOf(false, false, true, false), secondSent.messages().map { it.cached() }) },
            { assertEquals(MessageParam.Role.SYSTEM, secondSent.messages().last().role()) },
        )
    }

    @Test
    fun `도구 라운드에서는 맥락 뒤에 도구 호출과 결과가 이어진다`() {
        val prepared = chat.prepare(null, "기억해줘: 나는 아침형이야")
        val sent = messageService.streamsInOrder(
            chatStream { start(); toolUse("save_memory", mapOf("category" to "FACT", "content" to "사용자는 아침형이다")); end("tool_use") },
            chatStream { start(); text("기억했어요."); end("end_turn") },
        )
        chat.respond(prepared) { }

        val roles = sent[1].messages().map { it.role() }
        assertAll(
            { assertEquals(listOf(MessageParam.Role.USER, MessageParam.Role.SYSTEM, MessageParam.Role.ASSISTANT, MessageParam.Role.USER), roles) },
            { assertTrue(sent[1].cacheControl().isPresent) },
            { assertEquals(sent[0].messages(), sent[1].messages().take(2)) },
        )
    }
}

/** 대화 중 system 메시지를 끄면 예전처럼 맥락을 시스템 프롬프트에 넣는다 */
@SpringBootTest(properties = ["claude.context-as-system-message=false"])
@Import(TestcontainersConfiguration::class)
class ChatPromptCacheFallbackTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var chat: ChatService

    private val messageService: MessageService = mock(MessageService::class.java)

    @Test
    fun `맥락은 시스템 두 번째 블록에 들어가고 고정 지시만 캐시 지점을 가진다`() {
        `when`(client.messages()).thenReturn(messageService)
        val prepared = chat.prepare(null, "안녕")
        val sent = messageService.streamsInOrder(chatStream { start(); text("안녕하세요"); end("end_turn") })
        chat.respond(prepared) { }
        val params: MessageCreateParams = sent.single()

        val system = params.system().get().asTextBlockParams()
        assertAll(
            { assertEquals(2, system.size) },
            { assertTrue(system[0].cacheControl().isPresent) },
            { assertTrue(system[1].text().contains("## 현재 시각 (한국)")) },
            { assertEquals(listOf(MessageParam.Role.USER), params.messages().map { it.role() }) },
        )
    }
}
