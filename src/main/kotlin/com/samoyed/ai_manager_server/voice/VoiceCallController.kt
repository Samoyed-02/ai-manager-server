package com.samoyed.ai_manager_server.voice

import com.samoyed.ai_manager_server.chat.ChatMessage
import com.samoyed.ai_manager_server.chat.ChatMessageRepository
import com.samoyed.ai_manager_server.chat.Conversation
import com.samoyed.ai_manager_server.chat.ConversationChannel
import com.samoyed.ai_manager_server.chat.ConversationRepository
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import org.springframework.http.HttpStatus
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.bind.annotation.*
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

const val MAX_CALL_TURNS = 500
const val MAX_CALL_TURN_TEXT = 5_000

data class VoiceTurn(
    @field:Pattern(regexp = "user|assistant", message = "user 또는 assistant만 가능합니다")
    val role: String,

    @field:NotBlank(message = "비어 있을 수 없습니다")
    @field:Size(max = MAX_CALL_TURN_TEXT, message = "최대 {max}자까지 입력할 수 있습니다")
    val text: String,
)

data class VoiceCallRequest(
    val startedAt: OffsetDateTime,
    val endedAt: OffsetDateTime? = null,

    @field:Size(min = 1, max = MAX_CALL_TURNS, message = "발화는 {min}~{max}개여야 합니다")
    val turns: List<@Valid VoiceTurn>,
)

data class VoiceCallResponse(val conversationId: UUID)

/**
 * 통화가 끝나면 클라이언트가 음성을 받아 적은 기록을 보낸다.
 * 채팅과 같은 대화로 저장해 기억 추출 워커가 처리하고, 다음 통화·채팅의 맥락에 들어간다
 */
@RestController
class VoiceCallController(
    private val conversations: ConversationRepository,
    private val messages: ChatMessageRepository,
    private val tx: TransactionTemplate,
) {
    private val titleFormat = DateTimeFormatter.ofPattern("통화 M월 d일 HH:mm")

    @PostMapping("/voice/calls")
    @ResponseStatus(HttpStatus.CREATED)
    fun save(@Valid @RequestBody req: VoiceCallRequest): VoiceCallResponse {
        val title = req.startedAt.atZoneSameInstant(ZoneId.of("Asia/Seoul")).format(titleFormat)
        val conversation = tx.execute {
            val conversation = conversations.save(Conversation(title = title, channel = ConversationChannel.VOICE))
            messages.saveAll(req.turns.map { ChatMessage(conversation.id, it.role, it.text.trim()) })
            conversation
        }!!
        return VoiceCallResponse(conversation.id)
    }
}
