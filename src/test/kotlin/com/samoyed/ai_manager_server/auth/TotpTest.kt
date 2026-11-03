package com.samoyed.ai_manager_server.auth

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class TotpTest {
    /** RFC 6238 부록 B의 SHA1 비밀값 */
    private val secret = "12345678901234567890".toByteArray()

    @Test
    fun `RFC 6238 테스트 값과 같다`() {
        mapOf(
            59L to "94287082", 1111111109L to "07081804", 1111111111L to "14050471",
            1234567890L to "89005924", 2000000000L to "69279037", 20000000000L to "65353130",
        ).forEach { (time, expected) ->
            assertEquals(expected, Totp.code(secret, Totp.counterAt(time), digits = 8), "t=$time")
            // 6자리는 8자리 값의 뒤 6자리
            assertEquals(expected.takeLast(6), Totp.code(secret, Totp.counterAt(time)), "t=$time")
        }
    }

    @Test
    fun `앞뒤 30초까지 허용하고 그 밖이나 형식이 틀리면 거절한다`() {
        val t = 1111111111L
        val code = Totp.code(secret, Totp.counterAt(t))
        assertEquals(Totp.counterAt(t), Totp.verify(secret, code, t))
        assertEquals(Totp.counterAt(t), Totp.verify(secret, code, t + 30))
        assertEquals(Totp.counterAt(t), Totp.verify(secret, code, t - 30))
        assertNull(Totp.verify(secret, code, t + 60))
        assertNull(Totp.verify(secret, "12345", t))
        assertNull(Totp.verify(secret, "abcdef", t))
    }

    @Test
    fun `base32 변환`() {
        assertArrayEquals(secret, Totp.decodeBase32("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ"))
        assertArrayEquals(secret, Totp.decodeBase32("gezd gnbv gy3t qojq gezd gnbv gy3t qojq"))
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Totp.encodeBase32(secret))
        val generated = Totp.generateSecret()
        assertEquals(20, Totp.decodeBase32(generated).size)
    }
}
