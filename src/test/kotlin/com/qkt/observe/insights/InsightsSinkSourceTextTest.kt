package com.qkt.observe.insights

import java.net.InetAddress
import java.util.concurrent.TimeUnit
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class InsightsSinkSourceTextTest {
    @Test
    fun `one sink sends a strategy's source text once per sha, and the sha on every start`() {
        val server = MockWebServer().also { it.start(InetAddress.getByName("127.0.0.1"), 0) }
        repeat(3) { server.enqueue(MockResponse().setResponseCode(200).setBody("""{"accepted":1}""")) }
        val sink =
            InsightsSink(
                url = "http://127.0.0.1:${server.port}/ingest",
                token = "secret",
                instanceId = "qkt-test",
                flushIntervalMs = 20L,
            )
        val meta = mapOf("sourceSha256" to "aa", "sourceText" to "STRATEGY gold VERSION 1", "sourceTruncated" to false)
        try {
            sink.offer(InsightsTranslate.strategyStarted("gold", 1L, meta))
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()
            sink.offer(InsightsTranslate.strategyStarted("gold", 2L, meta))
            val second = server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8()

            assertThat(first).contains(""""sourceText":"STRATEGY gold VERSION 1"""")
            assertThat(second).contains(""""sourceSha256":"aa"""", """"sourceTextOmitted":true""")
            assertThat(second).doesNotContain("sourceText\"")
        } finally {
            sink.close()
            server.shutdown()
        }
    }
}
