package com.samoyed.ai_manager_server.chat

import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.Message
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.MessageDeltaUsage
import com.anthropic.models.messages.RawContentBlockDelta
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageDeltaEvent
import com.anthropic.models.messages.RawMessageStartEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextDelta
import com.anthropic.models.messages.Usage
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.task.anyArg
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import java.util.Optional
import java.util.stream.Stream

/** messageService.createStreaming(...)이 주어진 이벤트를 흘려보내도록 stub한다 */
fun MessageService.streams(events: Stream<RawMessageStreamEvent>) {
    @Suppress("UNCHECKED_CAST")
    val response = mock(StreamResponse::class.java) as StreamResponse<RawMessageStreamEvent>
    `when`(response.stream()).thenReturn(events)
    `when`(createStreaming(anyArg(MessageCreateParams::class.java))).thenReturn(response)
}

/** 텍스트 조각 이벤트 (content_block_delta) */
fun textDelta(text: String): RawMessageStreamEvent {
    val textDelta = mock(TextDelta::class.java)
    `when`(textDelta.text()).thenReturn(text)
    val delta = mock(RawContentBlockDelta::class.java)
    `when`(delta.text()).thenReturn(Optional.of(textDelta))
    val blockDelta = mock(RawContentBlockDeltaEvent::class.java)
    `when`(blockDelta.delta()).thenReturn(delta)
    val event = mock(RawMessageStreamEvent::class.java)
    `when`(event.contentBlockDelta()).thenReturn(Optional.of(blockDelta))
    return event
}

/** 종료 이유 이벤트 (message_delta) */
fun stop(reason: StopReason, outputTokens: Long = 10): RawMessageStreamEvent {
    val delta = mock(RawMessageDeltaEvent.Delta::class.java)
    `when`(delta.stopReason()).thenReturn(Optional.of(reason))
    val usage = mock(MessageDeltaUsage::class.java)
    `when`(usage.outputTokens()).thenReturn(outputTokens)
    val messageDelta = mock(RawMessageDeltaEvent::class.java)
    `when`(messageDelta.delta()).thenReturn(delta)
    `when`(messageDelta.usage()).thenReturn(usage)
    val event = mock(RawMessageStreamEvent::class.java)
    `when`(event.messageDelta()).thenReturn(Optional.of(messageDelta))
    return event
}

/** 응답 시작 이벤트 (message_start). 입력 토큰 수가 여기에 온다 */
fun messageStart(inputTokens: Long = 100, cacheReadTokens: Long? = null): RawMessageStreamEvent {
    val usage = mock(Usage::class.java)
    `when`(usage.inputTokens()).thenReturn(inputTokens)
    `when`(usage.cacheReadInputTokens()).thenReturn(Optional.ofNullable(cacheReadTokens))
    val message = mock(Message::class.java)
    `when`(message.usage()).thenReturn(usage)
    val start = mock(RawMessageStartEvent::class.java)
    `when`(start.message()).thenReturn(message)
    val event = mock(RawMessageStreamEvent::class.java)
    `when`(event.messageStart()).thenReturn(Optional.of(start))
    return event
}
