package com.samoyed.ai_manager_server.config

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class AnthropicConfig {
    @Bean
    fun anthropicClient(@Value("\${anthropic.api-key}") apiKey: String): AnthropicClient =
        AnthropicOkHttpClient.builder().apiKey(apiKey).build()
}