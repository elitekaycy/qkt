package com.qkt.marketdata.store.binance

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BinanceFundingRatesTest {
    private lateinit var server: MockWebServer
    private lateinit var rates: BinanceFundingRates

    @BeforeEach
    fun setup() {
        server = MockWebServer().also { it.start() }
        rates = BinanceFundingRates(apiBaseUrl = server.url("/").toString().trimEnd('/'))
    }

    @AfterEach
    fun teardown() = server.shutdown()

    private fun payments(times: LongRange): String =
        times.joinToString(",", "[", "]") {
            """{"symbol":"BTCUSDT","fundingTime":$it,"fundingRate":"0.0001","markPrice":"60000.5"}"""
        }

    @Test
    fun `a short page ends the read and asks for the venue symbol over the whole range`() {
        server.enqueue(MockResponse().setBody(payments(10L..12L)))

        val read = rates.rates("BINANCE_UM:BTCUSDT", 5L, 99L)

        assertThat(read.map { it.timeMs }).containsExactly(10L, 11L, 12L)
        assertThat(read.first().price).isEqualByComparingTo("60000.5")
        val path = server.takeRequest().path!!
        assertThat(path).startsWith("/fapi/v1/fundingRate?symbol=BTCUSDT&startTime=5&endTime=99&limit=1000")
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun `a full page is followed by a page starting after its last payment`() {
        server.enqueue(MockResponse().setBody(payments(1L..1000L)))
        server.enqueue(MockResponse().setBody(payments(1001L..1001L)))

        val read = rates.rates("BINANCE_UM:BTCUSDT", 1L, 5000L)

        assertThat(read).hasSize(1001)
        server.takeRequest()
        assertThat(server.takeRequest().path).contains("startTime=1001&endTime=5000")
    }

    @Test
    fun `an empty range reads nothing`() {
        assertThat(rates.rates("BINANCE_UM:BTCUSDT", 10L, 9L)).isEmpty()
        assertThat(server.requestCount).isZero()
    }

    @Test
    fun `a refused request fails naming the status and symbol`() {
        server.enqueue(MockResponse().setResponseCode(429).setBody("slow down"))

        assertThatThrownBy { rates.rates("BINANCE_UM:BTCUSDT", 1L, 2L) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("HTTP 429")
            .hasMessageContaining("BTCUSDT")
            .hasMessageContaining("slow down")
    }

    @Test
    fun `an empty mark price is read as no price`() {
        val parsed = BinanceFundingRates.parse("""[{"fundingTime":7,"fundingRate":"-0.0002","markPrice":""}]""")

        assertThat(parsed.single().price).isNull()
        assertThat(parsed.single().rate).isEqualByComparingTo("-0.0002")
    }

    @Test
    fun `the default source answers an empty range without calling the venue`() {
        assertThat(BinanceFundingRates().rates("BINANCE_UM:BTCUSDT", 2L, 1L)).isEmpty()
    }
}
