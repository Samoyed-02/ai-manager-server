package com.samoyed.ai_manager_server.memory

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SensitiveInfoFilterTest {

    @ParameterizedTest
    @ValueSource(
        strings = [
            "사용자의 주민번호는 900101-1234567이다",
            "카드번호 4111 1111 1111 1111로 결제한다",
            "카드 4111-1111-1111-1111",
            "월급 계좌는 110-123-456789이다",
            "전화번호는 010-1234-5678",
            "API 키는 sk-ant-api03-abcdefghijklmnopqrstuv",
            "AWS 키 AKIAIOSFODNN7EXAMPLE 사용",
            "깃허브 토큰 ghp_abcdefghijklmnopqrstuvwxyz0123",
            "토큰 a1b2c3d4e5f6a7b8c9d0e1f2a3b4c5d6e7f8",
            "비밀번호: abc123!",
            "와이파이 비번은 qwer1234",
            "password=hunter2!",
        ]
    )
    fun `민감 정보는 걸러낸다`(text: String) {
        assertTrue(SensitiveInfoFilter.isSensitive(text), text)
    }

    @ParameterizedTest
    @ValueSource(
        strings = [
            "사용자는 2026-12-01까지 정보처리기사 합격이 목표다",
            "사용자는 비밀번호를 자주 바꾸는 편이다",
            "사용자는 1Password manager를 쓴다",
            "사용자는 매일 3시간 알고리즘을 공부한다",
            "사용자는 백준 1234번 문제를 풀었다",
            "사용자는 토익 900점이 목표다",
            "카드 번호처럼 보이지만 검사에 실패하는 4111 1111 1111 1112",
            "사용자는 2027년 2월 졸업 예정이다",
            "사용자는 Spring Boot 4와 Kotlin 2.3을 쓴다",
        ]
    )
    fun `평범한 기억은 통과시킨다`(text: String) {
        assertFalse(SensitiveInfoFilter.isSensitive(text), text)
    }
}
