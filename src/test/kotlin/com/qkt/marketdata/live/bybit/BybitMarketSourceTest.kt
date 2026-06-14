package com.qkt.marketdata.live.bybit

import com.qkt.marketdata.source.MarketSourceCapability
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BybitMarketSourceTest {
    @Test
    fun `BybitSpotMarketSource supports BYBIT_SPOT prefix`() {
        val src = BybitSpotMarketSource(wsFactory = { FakeBybitWebSocket() })
        assertThat(src.supports("BYBIT_SPOT:BTCUSDT")).isTrue
        assertThat(src.supports("BYBIT_LINEAR:BTCUSDT")).isFalse
        assertThat(src.supports("EXNESS:XAUUSD")).isFalse
        assertThat(src.name).isEqualTo("Bybit:spot")
    }

    @Test
    fun `BybitLinearMarketSource supports BYBIT_LINEAR prefix`() {
        val src = BybitLinearMarketSource(wsFactory = { FakeBybitWebSocket() })
        assertThat(src.supports("BYBIT_LINEAR:BTCUSDT")).isTrue
        assertThat(src.supports("BYBIT_SPOT:BTCUSDT")).isFalse
        // #213: the data source must use the same prefix the broker accepts (BYBIT_LINEAR:).
        // It used to be BYBIT_PERP:, so a perp symbol got market data but no execution.
        assertThat(src.supports("BYBIT_PERP:BTCUSDT")).isFalse
        assertThat(src.name).isEqualTo("Bybit:linear")
    }

    @Test
    fun `both declare LIVE_TICKS, BARS, and VOLUME`() {
        for (src in listOf(
            BybitSpotMarketSource(wsFactory = {
                FakeBybitWebSocket()
            }),
            BybitLinearMarketSource(wsFactory = { FakeBybitWebSocket() }),
        )) {
            assertThat(src.capabilities)
                .containsExactlyInAnyOrder(
                    MarketSourceCapability.LIVE_TICKS,
                    MarketSourceCapability.BARS,
                    MarketSourceCapability.VOLUME,
                )
        }
    }

    @Test
    fun `liveTicks strips prefix before subscribing`() {
        val fakeWs = FakeBybitWebSocket()
        val src = BybitSpotMarketSource(wsFactory = { fakeWs })
        val feed = src.liveTicks(listOf("BYBIT_SPOT:BTCUSDT"))
        feed.close()
        // After subscribe, the WS should have been told about bare BTCUSDT
        assertThat(fakeWs.sentTexts).hasSize(1)
        assertThat(fakeWs.sentTexts[0]).contains("\"tickers.BTCUSDT\"")
        assertThat(fakeWs.sentTexts[0]).contains("\"publicTrade.BTCUSDT\"")
    }
}
