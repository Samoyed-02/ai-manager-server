package com.samoyed.ai_manager_server.config

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableScheduling

/** 테스트에서는 app.scheduling.enabled=false로 꺼서 워커를 직접 호출한다 */
@Configuration
@EnableScheduling
@ConditionalOnProperty("app.scheduling.enabled", matchIfMissing = true)
class SchedulingConfig
