package com.samoyed.ai_manager_server.chat

import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.RawContentBlockDelta
import com.anthropic.models.messages.RawContentBlockDeltaEvent
import com.anthropic.models.messages.RawMessageDeltaEvent
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.models.messages.StopReason
import com.anthropic.models.messages.TextDelta
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
fun stop(reason: StopReason): RawMessageStreamEvent {
    val delta = mock(RawMessageDeltaEvent.Delta::class.java)
    `when`(delta.stopReason()).thenReturn(Optional.of(reason))
    val messageDelta = mock(RawMessageDeltaEvent::class.java)
    `when`(messageDelta.delta()).thenReturn(delta)
    val event = mock(RawMessageStreamEvent::class.java)
    `when`(event.messageDelta()).thenReturn(Optional.of(messageDelta))
    return event
}
