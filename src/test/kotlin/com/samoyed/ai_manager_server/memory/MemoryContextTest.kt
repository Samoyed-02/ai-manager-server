package com.samoyed.ai_manager_server.memory

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import com.samoyed.ai_manager_server.profile.ContextService
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertAll
import org.mockito.Answers
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.test.context.bean.override.mockito.MockitoBean
import java.util.UUID

/** 기억과 대화 요약이 채팅·통화 맥락에 들어가는지 */
@SpringBootTest
@Import(TestcontainersConfiguration::class)
class MemoryContextTest {

    @MockitoBean(answers = Answers.RETURNS_SELF)
    lateinit var client: AnthropicClient

    @Autowired lateinit var context: ContextService
    @Autowired lateinit var memories: MemoryRepository
    @Autowired lateinit var conversations: ConversationRepository
    @Autowired lateinit var jdbc: JdbcClient

    @BeforeEach
    fun setUp() {
        jdbc.sql("delete from memories").update()
        jdbc.sql("delete from conversations").update()
    }

    @Test
    fun `채팅 맥락에 기억과 다른 대화 요약이 들어가고 지금 대화 요약은 빠진다`() {
        memories.save(Memory(MemoryCategory.GOAL, "사용자는 정보처리기사 합격이 목표다"))
        memories.save(Memory(MemoryCategory.ROUTINE, "사용자는 매일 아침 7시에 일어난다"))
        val call = summarized(ConversationChannel.VOICE, "어제 통화에서 공부 계획을 세움.")
        val current = summarized(ConversationChannel.CHAT, "지금 대화의 요약.")

        val text = context.forClaude(current)

        assertAll(
            { assertTrue(text.contains("### 루틴\n- 사용자는 매일 아침 7시에 일어난다"), text) },
            { assertTrue(text.contains("### 목표\n- 사용자는 정보처리기사 합격이 목표다"), text) },
            { assertTrue(text.contains("통화: 어제 통화에서 공부 계획을 세움."), text) },
            { assertFalse(text.contains("지금 대화의 요약."), text) },
            { assertTrue(call != current) },
        )
    }

    @Test
    fun `통화 맥락에도 기억과 최근 대화 요약이 들어간다`() {
        memories.save(Memory(MemoryCategory.PREFERENCE, "사용자는 짧고 직설적인 답을 좋아한다"))
        summarized(ConversationChannel.CHAT, "채팅에서 이력서를 고침.")

        val text = context.forVoice()

        assertAll(
            { assertTrue(text.contains("기억하는 것:"), text) },
            { assertTrue(text.contains("- 사용자는 짧고 직설적인 답을 좋아한다"), text) },
            { assertTrue(text.contains("채팅: 채팅에서 이력서를 고침."), text) },
        )
    }

    private fun summarized(channel: ConversationChannel, summary: String): UUID {
        val conversation = conversations.save(Conversation(title = "테스트", channel = channel))
        jdbc.sql("update conversations set summary = :s where id = :id").param("s", summary).param("id", conversation.id).update()
        return conversation.id
    }
}
