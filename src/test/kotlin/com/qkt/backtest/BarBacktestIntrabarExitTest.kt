package com.qkt.backtest

import com.qkt.backtest.BarBacktestFixtures.candle
import com.qkt.backtest.BarBacktestFixtures.compile
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BarBacktestIntrabarExitTest {
    @Test
    fun `a synthesized intra-bar low fires a stop the close alone would not`() {
        // Entry bar closes at 105; protective stop at 95, take-profit parked at 200 (never hit).
        // The next bar's CLOSE is 100 (above the stop), but its LOW is 80. Only because the low is
        // replayed (O->L->H->C) does the stop fire.
        val source = InMemoryMarketSource()
        val candles =
            listOf(
                candle("100", "110", "99", "105", 0L),
                candle("105", "106", "80", "100", 60_000L),
            )
        source.seedBars("BYBIT_SPOT:BTCUSDT", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY stop_fidelity VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0 AND POSITION.btc = 0
                  THEN BUY btc BRACKET { STOP LOSS AT 95, TAKE PROFIT AT 200 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("stop_fidelity" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(2 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        assertThat(result.trades.map { it.trade.side }).contains(Side.SELL)
    }

    @Test
    fun `a short with stop and target inside one bar exits at the stop, not the target`() {
        // Short entered on bar 1 (close 100): stop above at 104, take-profit below at 96. Bar 2
        // spans 90..110 — BOTH levels are inside its range. Position-aware ordering replays the
        // short's adverse extreme (the High) first, so the stop at 104 must fire and cancel the
        // take-profit; the legacy Low-first order would optimistically book the target instead.
        val source = InMemoryMarketSource()
        val candles =
            listOf(
                candle("100", "101", "99", "100", 0L),
                candle("100", "110", "90", "100", 60_000L),
            )
        source.seedBars("BYBIT_SPOT:BTCUSDT", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY short_ambiguity VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BYBIT_SPOT:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > 0 AND POSITION.btc = 0
                  THEN SELL btc BRACKET { STOP LOSS AT 104, TAKE PROFIT AT 96 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("short_ambiguity" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BYBIT_SPOT:BTCUSDT"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(2 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        // Default (non --bars) fill model fills the triggered stop at the printing tick — here the
        // bar's High. The point pinned is WHICH leg fired: the stop (adverse, >= 104), never the
        // take-profit at 96 the legacy Low-first order would have booked.
        val exit = result.trades.map { it.trade }.first { it.side == Side.BUY }
        assertThat(exit.price).isGreaterThanOrEqualTo(Money.of("104"))
    }
}
