package com.qkt.observe.insights

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.LoggerContext
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class InsightsLogAppenderTest {
    private lateinit var server: MockWebServer
    private lateinit var sink: InsightsSink
    private lateinit var context: LoggerContext
    private lateinit var logger: Logger

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        repeat(5) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
        sink =
            InsightsSink(
                url = server.url("/ingest").toString(),
                token = "secret",
                instanceId = "qkt-test",
                batchSize = 100,
                flushIntervalMs = 50L,
                queueCapacity = 100,
            )
        // A private logback context so the test doesn't touch the global root logger.
        // The SLF4J-bound production context carries an MDC adapter automatically; a
        // hand-built one does not, and getMDCPropertyMap NPEs without it.
        context = LoggerContext()
        context.mdcAdapter =
            ch.qos.logback.classic.util
                .LogbackMDCAdapter()
        logger = context.getLogger("com.qkt.app.LiveSession")
        val appender = InsightsLogAppender(sink)
        appender.name = InsightsLogAppender.NAME
        appender.context = context
        appender.start()
        logger.addAppender(appender)
    }

    @AfterEach
    fun teardown() {
        sink.close()
        context.stop()
        server.shutdown()
    }

    @Test
    fun `ships info and above as log envelopes with mdc strategy attribution`() {
        // In production the context's adapter IS the global slf4j MDC; this private
        // test context has its own, so put the key through it directly.
        context.mdcAdapter.put("strategy", "latch")
        logger.info("engine started")
        logger.warn("stale symbol XAUUSD")
        logger.debug("noise that must not ship")
        context.mdcAdapter.clear()

        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(req).isNotNull
        val body = req!!.body.readUtf8()
        assertThat(body).contains(""""type":"log"""")
        assertThat(body).contains(""""level":"INFO"""")
        assertThat(body).contains(""""level":"WARN"""")
        assertThat(body).contains(""""message":"stale symbol XAUUSD"""")
        assertThat(body).contains(""""strategyId":"latch"""")
        assertThat(body).contains(""""logger":"com.qkt.app.LiveSession"""")
        assertThat(body).doesNotContain("noise that must not ship")
    }

    @Test
    fun `skips its own package so sink warnings cannot echo`() {
        val own = context.getLogger("com.qkt.observe.insights.InsightsSink")
        own.addAppender(logger.getAppender(InsightsLogAppender.NAME))
        own.warn("collector returned 500")
        logger.info("real line")

        val req = server.takeRequest(2, TimeUnit.SECONDS)
        assertThat(req).isNotNull
        val body = req!!.body.readUtf8()
        assertThat(body).contains("real line")
        assertThat(body).doesNotContain("collector returned 500")
    }
}
