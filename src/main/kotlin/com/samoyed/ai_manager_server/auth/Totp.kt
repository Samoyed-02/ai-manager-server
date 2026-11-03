package com.samoyed.ai_manager_server.auth

import java.net.URLEncoder
import java.nio.ByteBuffer
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** 인증 앱(Google Authenticator 등)의 6자리 코드. RFC 6238 (HMAC-SHA1, 30초, 6자리) */
object Totp {
    const val STEP_SECONDS = 30L
    private const val DIGITS = 6
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    fun counterAt(epochSeconds: Long): Long = epochSeconds / STEP_SECONDS

    /** counter 시점의 코드. digits는 RFC 테스트 값(8자리) 확인용 */
    fun code(secret: ByteArray, counter: Long, digits: Int = DIGITS): String {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(secret, "HmacSHA1")) }
        val hash = mac.doFinal(ByteBuffer.allocate(8).putLong(counter).array())
        val offset = hash.last().toInt() and 0x0f
        val binary = ((hash[offset].toInt() and 0x7f) shl 24) or
            ((hash[offset + 1].toInt() and 0xff) shl 16) or
            ((hash[offset + 2].toInt() and 0xff) shl 8) or
            (hash[offset + 3].toInt() and 0xff)
        var mod = 1
        repeat(digits) { mod *= 10 }
        return (binary % mod).toString().padStart(digits, '0')
    }

    /** 맞으면 맞은 counter, 아니면 null. 시계 오차를 감안해 앞뒤 window 단계까지 본다 */
    fun verify(secret: ByteArray, code: String, epochSeconds: Long, window: Int = 1): Long? {
        if (!code.matches(Regex("\\d{$DIGITS}"))) return null
        val now = counterAt(epochSeconds)
        return (-window..window).map { now + it }.firstOrNull { counter ->
            java.security.MessageDigest.isEqual(code(secret, counter).toByteArray(), code.toByteArray())
        }
    }

    fun decodeBase32(text: String): ByteArray {
        val clean = text.uppercase().replace("=", "").replace(" ", "")
        require(clean.isNotEmpty() && clean.all { it in ALPHABET }) { "base32 문자열이 아닙니다" }
        var buffer = 0
        var bits = 0
        val out = java.io.ByteArrayOutputStream()
        for (c in clean) {
            buffer = (buffer shl 5) or ALPHABET.indexOf(c)
            bits += 5
            if (bits >= 8) {
                out.write((buffer shr (bits - 8)) and 0xff)
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    fun encodeBase32(bytes: ByteArray): String {
        val sb = StringBuilder()
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                sb.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) sb.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return sb.toString()
    }

    /** 새 비밀값(160비트) */
    fun generateSecret(): String = encodeBase32(ByteArray(20).also { SecureRandom().nextBytes(it) })

    /** 인증 앱 등록용 URI. QR 코드로 만들어 찍는다 */
    fun otpauthUri(secret: String, account: String = "me", issuer: String = "AI Manager"): String {
        fun enc(s: String) = URLEncoder.encode(s, Charsets.UTF_8).replace("+", "%20")
        return "otpauth://totp/${enc(issuer)}:${enc(account)}?secret=$secret&issuer=${enc(issuer)}&algorithm=SHA1&digits=$DIGITS&period=$STEP_SECONDS"
    }
}
