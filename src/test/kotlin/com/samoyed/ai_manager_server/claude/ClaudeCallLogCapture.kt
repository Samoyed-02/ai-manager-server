package com.samoyed.ai_manager_server.claude

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory

/** `claude.usage` 로그를 모은다. 끝나면 close()로 떼어 낸다 */
class ClaudeCallLogCapture : AutoCloseable {
    private val logger = LoggerFactory.getLogger("claude.usage") as Logger
    private val appender = ListAppender<ILoggingEvent>().apply { start() }

    init {
        logger.addAppender(appender)
    }

    val events: List<ILoggingEvent> get() = appender.list.toList()

    /** 하나뿐인 로그의 key-value를 맵으로 */
    fun single(): Pair<String, Map<String, String>> {
        val event = events.single()
        return event.formattedMessage to event.keyValuePairs.orEmpty().associate { it.key to it.value.toString() }
    }

    override fun close() {
        logger.detachAppender(appender)
    }
}
