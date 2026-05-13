package com.qkt.marketdata.live.mt5

import com.qkt.broker.mt5.MT5BrokerProfile
import com.qkt.broker.mt5.SymbolPolicy
import com.qkt.marketdata.source.MarketSourceCapability
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class Mt5MarketSourceTest {
    private val exness =
        MT5BrokerProfile(
            name = "exness",
            gatewayUrl = "http://localhost:5002",
            symbolPolicy = SymbolPolicy(suffix = "m"),
            magic = 123,
        )

    @Test
    fun `supports only its own prefix`() {
        val src = Mt5MarketSource(exness)
        assertThat(src.supports("EXNESS:XAUUSD")).isTrue
        assertThat(src.supports("LATCH:XAUUSD")).isFalse
        assertThat(src.supports("BYBIT_SPOT:BTCUSDT")).isFalse
        assertThat(src.supports("OANDA:XAUUSD")).isFalse
        assertThat(src.supports("XAUUSD")).isFalse
    }

    @Test
    fun `declares LIVE_TICKS and BARS capabilities`() {
        val src = Mt5MarketSource(exness)
        assertThat(src.capabilities)
            .containsExactlyInAnyOrder(MarketSourceCapability.LIVE_TICKS, MarketSourceCapability.BARS)
    }

    @Test
    fun `name encodes the profile`() {
        assertThat(Mt5MarketSource(exness).name).isEqualTo("MT5:exness")
    }

    @Test
    fun `liveTicks translates qkt symbol to broker wire symbol`() {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(
                MockResponse().setBody(
                    """{"bid":4700.0,"ask":4700.3,"last":4700.1,"flags":6,"time":1778662794,"time_msc":1778662794911,"volume":0,"volume_real":0}""",
                ),
            )
            val profile = exness.copy(gatewayUrl = server.url("/").toString().trimEnd('/'), pollIntervalMs = 5L)
            val source = Mt5MarketSource(profile, calendar = null)
            val feed = source.liveTicks(listOf("EXNESS:XAUUSD"))
            val tick = feed.next()
            feed.close()
            assertThat(tick).isNotNull
            // wire symbol is XAUUSDm (suffix applied)
            assertThat(tick!!.symbol).isEqualTo("XAUUSDm")
            // path on the gateway should have hit /symbol_info_tick/XAUUSDm
            val req = server.takeRequest()
            assertThat(req.path).isEqualTo("/symbol_info_tick/XAUUSDm")
        } finally {
            server.shutdown()
        }
    }
}
