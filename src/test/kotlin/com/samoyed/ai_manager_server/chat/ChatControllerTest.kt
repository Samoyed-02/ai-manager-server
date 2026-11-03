package com.samoyed.ai_manager_server.chat

import com.anthropic.client.AnthropicClient
import com.anthropic.models.messages.StopReason
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.util.stream.Stream

/** POST /chat의 SSE 응답을 필터·컨트롤러까지 포함해 확인한다 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class ChatControllerTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var jdbc: JdbcClient

    private val messageService: MessageService = mock(MessageService::class.java)

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
        `when`(client.messages()).thenReturn(messageService)
    }

    @Test
    fun `SSE 이벤트가 meta, delta, done 순서로 온다`() {
        messageService.streams(Stream.of(textDelta("안녕"), textDelta("하세요"), stop(StopReason.END_TURN)))

        val result = mvc.post("/chat") {
            header("X-App-Passcode", "test-passcode")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":"인사해줘"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()

        // 응답은 가상 스레드에서 스트리밍되므로 emitter.complete()까지 기다린다
        result.getAsyncResult(10_000)
        val body = result.response.getContentAsString(Charsets.UTF_8)

        val meta = body.indexOf("event:meta")
        val firstDelta = body.indexOf("event:delta")
        val done = body.indexOf("event:done")
        assertAll(
            { assertTrue(meta >= 0 && firstDelta > meta && done > firstDelta, body) },
            { assertTrue(body.contains(""""text":"안녕"""") && body.contains(""""text":"하세요""""), body) },
            { assertTrue(body.contains("""{"status":"COMPLETE"}"""), body) },
        )
    }

    @Test
    fun `create_task로 작업을 만들면 task 이벤트가 간다`() {
        messageService.streamsInOrder(
            chatStream { start(); toolUse("create_task", mapOf("instruction" to "보고서 초안")); end("tool_use") },
            chatStream { start(); text("맡겼어요."); end("end_turn") },
        )

        val result = mvc.post("/chat") {
            header("X-App-Passcode", "test-passcode")
            contentType = MediaType.APPLICATION_JSON
            content = """{"message":"보고서 맡겨줘"}"""
        }.andExpect { request { asyncStarted() } }.andReturn()
        result.getAsyncResult(10_000)
        val body = result.response.getContentAsString(Charsets.UTF_8)

        assertAll(
            { assertTrue(body.indexOf("event:task") in 0 until body.indexOf("event:done"), body) },
            { assertTrue(Regex(""""taskId":"[0-9a-f-]{36}","instruction":"보고서 초안"""").containsMatchIn(body), body) },
        )
    }
}
