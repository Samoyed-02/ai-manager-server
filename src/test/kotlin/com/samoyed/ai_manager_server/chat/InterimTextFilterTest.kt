package com.samoyed.ai_manager_server.chat

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class InterimTextFilterTest {

    private val sent = mutableListOf<String>()
    private val filter = InterimTextFilter { sent += it }

    private fun output() = sent.joinToString("")

    @Test
    fun `서버 도구 앞에 혼자 나온 짧은 문장은 버린다`() {
        filter.textStart(); filter.text("Results are a JSON string; parse it.")
        assertEquals(36, filter.toolStart(serverTool = true))
        filter.otherStart()
        filter.textStart(); filter.text("이번 주 뉴스입니다.")
        filter.responseEnd()

        assertEquals("이번 주 뉴스입니다.", output())
    }

    @Test
    fun `우리 서버 도구 앞의 문장은 남기고 빈 줄로 나눈다`() {
        filter.textStart(); filter.text("기록할게요.")
        assertNull(filter.toolStart(serverTool = false))
        filter.responseEnd()
        filter.textStart(); filter.text("기록했어요.")
        filter.responseEnd()

        assertEquals("기록할게요.\n\n기록했어요.", output())
    }

    @Test
    fun `텍스트에 바로 이어진 블록은 답변이라 서버 도구 앞이어도 남긴다`() {
        filter.textStart(); filter.text("첫째 소식.")
        filter.textStart(); filter.text(" 같은 기사.")
        assertNull(filter.toolStart(serverTool = true))

        assertEquals("첫째 소식. 같은 기사.", output())
    }

    @Test
    fun `인용이 달린 텍스트는 서버 도구 앞이어도 남긴다`() {
        filter.textStart(); filter.cited(); filter.text("인용된 문장.")
        assertNull(filter.toolStart(serverTool = true))

        assertEquals("인용된 문장.", output())
    }

    @Test
    fun `150자를 넘으면 모아 둔 것을 보내고 나머지는 바로 보낸다`() {
        filter.textStart()
        filter.text("가".repeat(100))
        assertEquals("", output())
        filter.text("나".repeat(100))
        assertEquals("가".repeat(100) + "나".repeat(100), output())
        filter.text("다")
        assertEquals(listOf("가".repeat(100) + "나".repeat(100), "다"), sent)
        assertNull(filter.toolStart(serverTool = true))
    }

    @Test
    fun `응답이 짧은 텍스트로 끝나면 그대로 보낸다`() {
        filter.textStart(); filter.text("네.")
        assertEquals("", output())
        filter.responseEnd()

        assertEquals("네.", output())
    }
}
