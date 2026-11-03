package com.samoyed.ai_manager_server.auth

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder

const val BCRYPT_STRENGTH = 12

/** 배포 준비용 명령. ./gradlew hashPassword, ./gradlew totpSecret 으로 실행한다 */
fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "hash-password" -> {
            System.err.print("비밀번호: ")
            val password = System.console()?.readPassword()?.let { String(it) } ?: readlnOrNull()
            require(!password.isNullOrEmpty() && password.length >= 12) { "비밀번호는 12자 이상이어야 합니다" }
            println(BCryptPasswordEncoder(BCRYPT_STRENGTH).encode(password))
        }
        "totp-secret" -> {
            val secret = Totp.generateSecret()
            println("APP_TOTP_SECRET=$secret")
            println("인증 앱 등록 URI (QR 코드로 만들어 찍거나 앱에 직접 입력):")
            println(Totp.otpauthUri(secret))
        }
        else -> error("사용: hash-password | totp-secret")
    }
}
