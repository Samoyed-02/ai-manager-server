package com.samoyed.ai_manager_server.memory

/**
 * 기억에 남기면 안 되는 정보를 찾는다. 기억은 모든 채팅·통화의 맥락에 들어가므로 저장 전에 한 번 더 막는다.
 * 프롬프트에서도 제외시키지만, 모델이 놓친 경우를 위한 마지막 방어선이다. 걸리면 가리지 않고 통째로 버린다.
 */
object SensitiveInfoFilter {
    private val residentNumber = Regex("""(?<!\d)\d{6}[- ]?[1-4]\d{6}(?!\d)""")

    /** 카드번호 후보. 숫자만 남겨 13~19자리이고 Luhn 검사를 통과하면 카드번호로 본다 */
    private val cardCandidate = Regex("""(?<!\d)\d(?:[ -]?\d){12,18}(?!\d)""")

    /** 계좌·전화번호처럼 하이픈으로 나뉜 숫자열. 날짜(8자리)는 빼고 10자리 이상만 본다 */
    private val dashedNumber = Regex("""(?<!\d)\d{2,6}-\d{2,6}-\d{2,8}(?!\d)""")

    private val apiKey = Regex(
        """(?<![A-Za-z0-9])(?:sk-(?:ant-)?[A-Za-z0-9_-]{16,}|AKIA[0-9A-Z]{16}|gh[pousr]_[A-Za-z0-9]{20,}|xox[baprs]-[A-Za-z0-9-]{10,}|AIza[0-9A-Za-z_-]{35})"""
    )

    /** 글자와 숫자가 섞인 32자 이상의 무작위 문자열(토큰, 비밀 키) */
    private val longToken = Regex("""(?<![A-Za-z0-9])(?=[A-Za-z0-9_-]*\d)(?=[A-Za-z0-9_-]*[A-Za-z])[A-Za-z0-9_-]{32,}""")

    /** "비밀번호: abc123!" 처럼 비밀번호 단어 뒤에 숫자나 기호가 섞인 값이 오는 경우 */
    private val password = Regex(
        """(?i)(?:비밀번호|비번|패스워드|암호|password|passwd|pw)\s*(?:[:=]|은|는|이|가)?\s*(?=\S*[0-9!@#$%^&*])[A-Za-z0-9!@#$%^&*()_+\-=.]{4,}"""
    )

    fun isSensitive(text: String): Boolean =
        residentNumber.containsMatchIn(text) ||
            apiKey.containsMatchIn(text) ||
            longToken.containsMatchIn(text) ||
            password.containsMatchIn(text) ||
            dashedNumber.findAll(text).any { m -> m.value.count { it.isDigit() } >= 10 } ||
            cardCandidate.findAll(text).any { m -> luhn(m.value.filter { it.isDigit() }) }

    private fun luhn(digits: String): Boolean {
        if (digits.length !in 13..19) return false
        val sum = digits.reversed().mapIndexed { i, c ->
            val d = c - '0'
            if (i % 2 == 1) (d * 2).let { if (it > 9) it - 9 else it } else d
        }.sum()
        return sum % 10 == 0
    }
}
