package com.samoyed.ai_manager_server

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@EnableScheduling
class AiManagerServerApplication

fun main(args: Array<String>) {
	runApplication<AiManagerServerApplication>(*args)
}
