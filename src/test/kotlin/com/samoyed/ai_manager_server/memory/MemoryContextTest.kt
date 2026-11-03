package com.samoyed.ai_manager_server.memory

import com.anthropic.client.AnthropicClient
import com.samoyed.ai_manager_server.TestcontainersConfiguration
import com.samoyed.ai_manager_server.activity.ActivityCategory
import com.samoyed.ai_manager_server.activity.ActivityLog
import com.samoyed.ai_manager_server.activity.ActivityLogRepository
import com.samoyed.ai_manager_server.routine.Notification
import com.samoyed.ai_manager_server.routine.NotificationRepository
import com.samoyed.ai_manager_server.routine.RoutineChange
import com.samoyed.ai_manager_server.routine.RoutineKind
import com.samoyed.ai_manager_server.routine.RoutineSchedule
import com.samoyed.ai_manager_server.routine.RoutineService
import java.time.LocalDate
import java.time.LocalTime
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
    @Autowired lateinit var notifications: NotificationRepository
    @Autowired lateinit var routineService: RoutineService
    @Autowired lateinit var activities: ActivityLogRepository

    @BeforeEach
    fun setUp() {
        listOf("notifications", "routines", "activity_logs", "memories", "conversations").forEach { jdbc.sql("delete from $it").update() }
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

    @Test
    fun `읽지 않은 알림, 켜진 루틴, 최근 활동이 맥락에 들어간다`() {
        val read = notifications.save(Notification(null, "어제 브리핑", "이미 읽음"))
        read.readAt = java.time.OffsetDateTime.now()
        notifications.save(read)
        notifications.save(Notification(null, "아침 브리핑", "기출 1회분 풀기"))
        routineService.create("아침 브리핑", RoutineKind.BRIEFING, "오늘 할 일", LocalTime.of(8, 0), emptySet())
        val off = routineService.create("꺼진 루틴", RoutineKind.REMINDER, "m", LocalTime.of(9, 0), emptySet())
        routineService.update(off.id, RoutineChange(enabled = false))
        val today = LocalDate.now(RoutineSchedule.ZONE)
        activities.save(ActivityLog(ActivityCategory.STUDY, "알고리즘", 90, today))
        activities.save(ActivityLog(ActivityCategory.STUDY, "CS 복습", 60, today.minusDays(1)))
        activities.save(ActivityLog(ActivityCategory.EXERCISE, "오래전 운동", 30, today.minusDays(8)))

        val chat = context.forClaude()
        val voice = context.forVoice()

        assertAll(
            { assertTrue(chat.contains("아침 브리핑: 기출 1회분 풀기"), chat) },
            { assertFalse(chat.contains("이미 읽음"), chat) },
            { assertTrue(chat.contains("- 아침 브리핑 (매일 08:00, 브리핑)"), chat) },
            { assertFalse(chat.contains("꺼진 루틴"), chat) },
            { assertTrue(chat.contains("- 공부: 2건, 2시간 30분"), chat) },
            { assertFalse(chat.contains("오래전 운동"), chat) },
            { assertTrue(voice.contains("읽지 않은 알림"), voice) },
            { assertTrue(voice.contains("log_activity"), voice) },
        )
    }

    private fun summarized(channel: ConversationChannel, summary: String): UUID {
        val conversation = conversations.save(Conversation(title = "테스트", channel = channel))
        jdbc.sql("update conversations set summary = :s where id = :id").param("s", summary).param("id", conversation.id).update()
        return conversation.id
    }
}
