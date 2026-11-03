package com.samoyed.ai_manager_server.voice

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.chat.ChatMessageRepository
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.util.UUID

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
class VoiceCallApiTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var mvc: MockMvc
    @Autowired lateinit var conversations: ConversationRepository
    @Autowired lateinit var messages: ChatMessageRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from conversations").update()
    }

    @Test
    fun `통화 기록을 VOICE 대화로 저장한다`() {
        val body = mvc.post("/voice/calls") {
            header("X-App-Passcode", "test-passcode")
            contentType = MediaType.APPLICATION_JSON
            content = """
                {"startedAt":"2026-11-03T12:30:00Z","endedAt":"2026-11-03T12:40:00Z",
                 "turns":[{"role":"user","text":"오늘 알고리즘 2시간 했어"},{"role":"assistant","text":"잘했어요!"}]}
            """.trimIndent()
        }.andExpect { status { isCreated() } }.andReturn().response.getContentAsString(Charsets.UTF_8)

        val id = UUID.fromString(Regex(""""conversationId":"([0-9a-f-]{36})"""").find(body)!!.groupValues[1])
        val conversation = conversations.findById(id).get()
        assertAll(
            { assertEquals(ConversationChannel.VOICE, conversation.channel) },
            // 시작 시각을 한국 시간으로
            { assertEquals("통화 11월 3일 21:30", conversation.title) },
            {
                assertEquals(
                    listOf("user" to "오늘 알고리즘 2시간 했어", "assistant" to "잘했어요!"),
                    messages.findByConversationIdOrderByIdAsc(id).map { it.role to it.content },
                )
            },
        )
        mvc.get("/conversations") { header("X-App-Passcode", "test-passcode") }.andExpect {
            jsonPath("$[0].channel") { value("VOICE") }
        }
    }

    @Test
    fun `발화가 없거나 역할이 잘못되면 400`() {
        post("""{"startedAt":"2026-11-03T12:30:00Z","turns":[]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields.turns") { exists() }
        }
        post("""{"startedAt":"2026-11-03T12:30:00Z","turns":[{"role":"system","text":"x"}]}""").andExpect {
            status { isBadRequest() }
            jsonPath("$.fields['turns[0].role']") { exists() }
        }
        assertEquals(0, conversations.count())
    }

    private fun post(body: String) = mvc.post("/voice/calls") {
        header("X-App-Passcode", "test-passcode")
        contentType = MediaType.APPLICATION_JSON
        content = body
    }
}
