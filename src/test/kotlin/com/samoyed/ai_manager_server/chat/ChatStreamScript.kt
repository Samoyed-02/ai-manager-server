package com.samoyed.ai_manager_server.chat

import com.anthropic.core.jsonMapper
import com.anthropic.core.http.StreamResponse
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.RawMessageStreamEvent
import com.anthropic.services.blocking.MessageService
import com.samoyed.ai_manager_server.task.anyArg
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

/**
 * 실제 API가 보내는 모양의 JSON으로 만든 스트림 이벤트.
 * MessageAccumulator가 응답 전체를 다시 조립할 수 있어 pause_turn 이어 받기까지 확인할 수 있다
 */
class ChatStreamScript {
    private var index = 0
    private var toolId = 0
    val events = mutableListOf<RawMessageStreamEvent>()

    fun start(inputTokens: Long = 100) = add(
        """{"type":"message_start","message":{"id":"msg_test","type":"message","role":"assistant",
        "model":"claude-sonnet-5-5","content":[],"stop_reason":null,"stop_sequence":null,
        "usage":{"input_tokens":$inputTokens,"output_tokens":1}}}"""
    )

    /** 텍스트 블록. citation은 웹 검색 인용(url, title) */
    fun text(vararg chunks: String, citation: Pair<String, String>? = null) {
        val i = index++
        add("""{"type":"content_block_start","index":$i,"content_block":{"type":"text","text":""}}""")
        chunks.forEach {
            add("""{"type":"content_block_delta","index":$i,"delta":{"type":"text_delta","text":"$it"}}""")
        }
        citation?.let { (url, title) ->
            add(
                """{"type":"content_block_delta","index":$i,"delta":{"type":"citations_delta","citation":
                {"type":"web_search_result_location","url":"$url","title":"$title","encrypted_index":"enc","cited_text":"인용"}}}"""
            )
        }
        add("""{"type":"content_block_stop","index":$i}""")
    }

    fun webSearch(query: String, resultUrl: String = "https://example.com/result") {
        val id = serverToolUse("web_search", """{\"query\":\"$query\"}""")
        val i = index++
        add(
            """{"type":"content_block_start","index":$i,"content_block":{"type":"web_search_tool_result","tool_use_id":"$id",
            "content":[{"type":"web_search_result","url":"$resultUrl","title":"검색 결과","encrypted_content":"enc","page_age":null}]}}"""
        )
        add("""{"type":"content_block_stop","index":$i}""")
    }

    fun webFetch(url: String, title: String) {
        val id = serverToolUse("web_fetch", """{\"url\":\"$url\"}""")
        val i = index++
        add(
            """{"type":"content_block_start","index":$i,"content_block":{"type":"web_fetch_tool_result","tool_use_id":"$id",
            "content":{"type":"web_fetch_result","url":"$url","retrieved_at":"2026-11-03T00:00:00Z",
            "content":{"type":"document","source":{"type":"text","media_type":"text/plain","data":"본문"},"title":"$title"}}}}"""
        )
        add("""{"type":"content_block_stop","index":$i}""")
    }

    fun end(stopReason: String, outputTokens: Long = 10, searches: Long = 0, fetches: Long = 0) {
        add(
            """{"type":"message_delta","delta":{"stop_reason":"$stopReason","stop_sequence":null},
            "usage":{"output_tokens":$outputTokens,"server_tool_use":{"web_search_requests":$searches,"web_fetch_requests":$fetches}}}"""
        )
        add("""{"type":"message_stop"}""")
    }

    private fun serverToolUse(name: String, inputJson: String): String {
        val id = "srvtoolu_${toolId++}"
        val i = index++
        add("""{"type":"content_block_start","index":$i,"content_block":{"type":"server_tool_use","id":"$id","name":"$name","input":{}}}""")
        add("""{"type":"content_block_delta","index":$i,"delta":{"type":"input_json_delta","partial_json":"$inputJson"}}""")
        add("""{"type":"content_block_stop","index":$i}""")
        return id
    }

    private fun add(json: String) {
        events += jsonMapper().readValue(json, RawMessageStreamEvent::class.java)
    }
}

fun chatStream(script: ChatStreamScript.() -> Unit): List<RawMessageStreamEvent> =
    ChatStreamScript().apply(script).events

/**
 * createStreaming(...)이 호출될 때마다 주어진 스트림을 차례로 돌려준다.
 * 돌려받은 리스트에 실제로 보낸 요청이 순서대로 쌓인다
 */
fun MessageService.streamsInOrder(vararg streams: List<RawMessageStreamEvent>): List<MessageCreateParams> {
    val sent = mutableListOf<MessageCreateParams>()
    `when`(createStreaming(anyArg(MessageCreateParams::class.java))).thenAnswer { invocation ->
        sent += invocation.getArgument<MessageCreateParams>(0)
        val events = streams[sent.size - 1]
        @Suppress("UNCHECKED_CAST")
        val response = mock(StreamResponse::class.java) as StreamResponse<RawMessageStreamEvent>
        `when`(response.stream()).thenAnswer { events.stream() }
        response
    }
    return sent
}
