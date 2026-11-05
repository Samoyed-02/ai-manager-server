package com.samoyed.ai_manager_server.chat

/** 텍스트 블록의 앞부분을 이만큼 모아 두었다가, 서버 도구로 이어지지 않는 것이 확인되면 보낸다 */
private const val HOLD_CHARS = 150

/**
 * 서버 도구(웹 검색·코드 실행·페이지 읽기) 바로 앞에 모델이 남긴 짧은 진행 문장을 사용자에게 보내지 않는다.
 * 예: 동적 필터링 코드 전에 나온 "Results are a JSON string; parse it."
 * 응답 시작이나 도구 바로 뒤에 혼자 나온 텍스트 블록은 앞의 HOLD_CHARS자를 모아 두고,
 * 그 안에서 블록이 끝난 뒤 서버 도구가 시작되면 버린다. 텍스트에 바로 이어진 블록이나 인용이 달린 텍스트는 답변이라 바로 보낸다.
 * 도구를 사이에 두고 이어지는 텍스트는 빈 줄로 나눠 문장이 붙지 않게 한다.
 * pause_turn·도구 라운드로 요청이 여러 번이어도 답변 하나에 하나를 쓴다
 */
internal class InterimTextFilter(private val emit: (String) -> Unit) {
    private val held = StringBuilder()
    private var holding = false
    private var lastEmitted: Char? = null
    private var toolSinceText = false
    private var afterText = false

    /** 새 텍스트 블록이 시작됐다 */
    fun textStart() {
        release()
        holding = !afterText
        afterText = true
    }

    /** 인용이 달린 텍스트는 답변의 일부다 */
    fun cited() = release()

    fun text(delta: String) {
        if (!holding) return send(delta)
        held.append(delta)
        if (held.length > HOLD_CHARS) release()
    }

    /** 도구 블록이 시작됐다. 서버 도구 앞의 짧은 문장은 버리고, 버린 글자 수를 돌려준다 */
    fun toolStart(serverTool: Boolean): Int? {
        val dropped = if (holding && serverTool) held.length.also { held.clear(); holding = false } else null
        release()
        toolSinceText = true
        afterText = false
        return dropped
    }

    /** 텍스트·도구가 아닌 블록(thinking, 도구 결과)이 시작됐다 */
    fun otherStart() {
        release()
        afterText = false
    }

    /** 응답 하나가 끝났다. 버릴지는 응답 안에서만 정하므로 모아 둔 텍스트를 보낸다 */
    fun responseEnd() {
        release()
        afterText = false
    }

    /** 모아 둔 텍스트를 보낸다 */
    fun release() {
        holding = false
        if (held.isEmpty()) return
        val text = held.toString()
        held.clear()
        send(text)
    }

    private fun send(delta: String) {
        if (delta.isEmpty()) return
        if (toolSinceText && lastEmitted != null && !lastEmitted!!.isWhitespace() && !delta.first().isWhitespace()) emit("\n\n")
        toolSinceText = false
        emit(delta)
        lastEmitted = delta.last()
    }
}
