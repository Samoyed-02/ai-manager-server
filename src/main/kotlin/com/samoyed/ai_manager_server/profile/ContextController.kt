package com.samoyed.ai_manager_server.profile

import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

data class ContextDto(val text: String)

@RestController
class ContextController(private val context: ContextService) {
    @GetMapping("/context/voice")
    fun voice() = ContextDto(context.forVoice())
}
