package com.qkt.marketdata.store.binance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BinanceOpenInterestTest {
    private val period = 300_000L
    private val now = 40L * 86_400_000L
    private lateinit var server: MockWebServer
    private lateinit var source: BinanceOpenInterest

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        source = BinanceOpenInterest(apiBaseUrl = server.url("/").toString().trimEnd('/'), clock = { now })
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun figures(vararg stamps: Long): String =
        stamps.joinToString(",", "[", "]") { """{"timestamp":$it,"sumOpenInterest":"123.5"}""" }

    @Test
    fun `figures are stamped at period end and trimmed to the asked range`() {
        val from = now - period * 10
        val to = from + period * 2
        server.enqueue(MockResponse().setBody(figures(from - period, from, from + period, from + 2 * period)))

        val read = source.figures("BINANCE_UM:BTCUSDT", from, to)

        assertThat(read.map { it.timeMs }).containsExactly(from, from + period, from + 2 * period)
        assertThat(read.first().openInterest).isEqualByComparingTo("123.5")
        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/futures/data/openInterestHist?symbol=BTCUSDT&period=5m")
        assertThat(path).contains("startTime=${from - period}&endTime=${to - period}&limit=500")
    }

    @Test
    fun `a range longer than one page is read in spans of five hundred periods`() {
        val from = now - period * 900
        val to = now
        server.enqueue(MockResponse().setBody("[]"))
        server.enqueue(MockResponse().setBody("[]"))

        assertThat(source.figures("BINANCE_UM:BTCUSDT", from, to)).isEmpty()

        val firstEnd = from - period + 500 * period - 1
        assertThat(server.takeRequest().path).contains("endTime=$firstEnd")
        assertThat(server.takeRequest().path).contains("startTime=${firstEnd + 1}&endTime=${to - period}")
    }

    @Test
    fun `a start older than thirty days is refused before any request`() {
        assertThatThrownBy { source.figures("BINANCE_UM:BTCUSDT", now - 31L * 86_400_000L, now) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("last 30 days")
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `a refused request fails naming the status and symbol`() {
        server.enqueue(MockResponse().setResponseCode(400).setBody("bad"))

        assertThatThrownBy { source.figures("BINANCE_UM:ETHUSDT", now - period, now) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HTTP 400")
            .hasMessageContaining("ETHUSDT")
    }
}
