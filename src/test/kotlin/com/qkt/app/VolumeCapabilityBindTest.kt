package com.qkt.app

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.TimeRange
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketRequest
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test

/**
 * #301 — a volume-weighted indicator (VWAP/OBV) bound to a feed that can't supply volume must be
 * rejected at deploy (TradingPipeline init), not silently never become ready.
 */
class VolumeCapabilityBindTest {
    private class FakeSource(
        override val capabilities: Set<MarketSourceCapability>,
        private val sample: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"

        override fun supports(symbol: String): Boolean = true

        override fun ticks(
            symbol: String,
            range: TimeRange,
        ): Sequence<Tick> = sample.asSequence().map { it.copy(symbol = symbol) }
    }

    private val sample: List<Tick> =
        listOf("100", "101", "102", "103", "104").mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L, volume = BigDecimal.ONE)
        }

    private val vwapStrategy =
        AstCompiler().compile(
            (
                Dsl.parse(
                    """
                    STRATEGY vwap_vol VERSION 1
                    DEFAULTS { SIZING = 1 TIF = GTC }
                    SYMBOLS
                      btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                      WHEN btc.close > vwap(btc.tick, 3) AND POSITION.btc = 0
                      THEN BUY btc
                    """.trimIndent(),
                ) as ParseResult.Success
            ).value,
        )

    private fun runOn(caps: Set<MarketSourceCapability>) =
        Backtest
            .fromSource(
                strategies = listOf("vwap_vol" to vwapStrategy),
                source = FakeSource(caps, sample),
                request =
                    MarketRequest(
                        symbols = listOf("BACKTEST:BTCUSDT"),
                        from = Instant.ofEpochMilli(0),
                        to = Instant.ofEpochMilli(10 * 60_000L),
                    ),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

    @Test
    fun `vwap on a feed without VOLUME is rejected at deploy`() {
        val ex = catchThrowable { runOn(setOf(MarketSourceCapability.TICKS)) }
        assertThat(ex).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(ex.message).contains("does not supply volume").contains("BACKTEST:BTCUSDT")
    }

    @Test
    fun `vwap on a feed that declares VOLUME deploys`() {
        // Should not throw.
        runOn(setOf(MarketSourceCapability.TICKS, MarketSourceCapability.VOLUME))
    }
}
