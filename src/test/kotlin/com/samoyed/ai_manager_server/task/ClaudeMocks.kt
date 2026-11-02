package com.samoyed.ai_manager_server.task

import com.anthropic.models.messages.ContentBlock
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.RefusalStopDetails
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextBlock
import com.anthropic.models.messages.Usage
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional

/** Claude 응답 mock. 기본값은 정상 종료(end_turn)한 짧은 문서 */
fun claudeResponse(
    text: String = "# 테스트 문서\n본문",
    stopReason: StopReason = StopReason.END_TURN,
    outputTokens: Long = 10,
    refusalCategory: String? = null,
): Message {
    val textBlock = mock(TextBlock::class.java)
    `when`(textBlock.text()).thenReturn(text)
    val block = mock(ContentBlock::class.java)
    `when`(block.text()).thenReturn(Optional.of(textBlock))
    val usage = mock(Usage::class.java)
    `when`(usage.outputTokens()).thenReturn(outputTokens)

    val message = mock(Message::class.java)
    `when`(message.content()).thenReturn(listOf(block))
    `when`(message.stopReason()).thenReturn(Optional.of(stopReason))
    `when`(message.usage()).thenReturn(usage)
    if (refusalCategory != null) {
        val details = mock(RefusalStopDetails::class.java)
        `when`(details.category()).thenReturn(Optional.of(RefusalStopDetails.Category.of(refusalCategory)))
        `when`(message.stopDetails()).thenReturn(Optional.of(details))
    }
    return message
}

/** Kotlin으로 작성된 SDK는 null 인자를 거부하므로 matcher만 등록하고 null을 그대로 넘긴다 */
@Suppress("UNCHECKED_CAST")
fun <T> anyArg(type: Class<T>): T {
    any(type)
    return null as T
}
