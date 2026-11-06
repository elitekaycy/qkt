package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class InsightsLogAppenderAttachTest {
    @Test
    fun `attach adds one started appender to the root logger and is idempotent`() {
        val root = LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME) as ch.qos.logback.classic.Logger
        val sink = InsightsSink(url = "http://127.0.0.1:9/ingest", token = "t", instanceId = "i", healthIntervalMs = 0L)
        val other = InsightsSink(url = "http://127.0.0.1:9/ingest", token = "t", instanceId = "j", healthIntervalMs = 0L)
        try {
            InsightsLogAppender.attach(sink)
            val first = root.getAppender(InsightsLogAppender.NAME)
            InsightsLogAppender.attach(other)

            assertThat(first).isInstanceOf(InsightsLogAppender::class.java)
            assertThat(first.isStarted).isTrue
            assertThat(root.getAppender(InsightsLogAppender.NAME)).isSameAs(first)
        } finally {
            root.getAppender(InsightsLogAppender.NAME)?.stop()
            root.detachAppender(InsightsLogAppender.NAME)
            sink.close()
            other.close()
        }
    }
}
