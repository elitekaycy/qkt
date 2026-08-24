package com.qkt.backtest

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketRequest
import com.qkt.strategy.Strategy
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reproduction attempt for the claimed "total re-entry stall after a bracket-triggered
 * exit": price rises (entry), falls through the stop (bracket SL exit), then rises
 * again — the `POSITION == 0` re-entry condition must produce a SECOND entry. If the
 * cancelled OCO sibling left anything stale that blocks re-entry, this fails.
 */
class BracketReentryReproTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `a bracket SL exit is followed by a clean re-entry when POSITION returns to zero`() {
        val source = InMemoryMarketSource()
        // 0..19: flat-ish warmup drift up; 20: entry bar; 21-24: fall through the stop;
        // 25..39: recover and drift up (second entry window).
        val closes =
            (0 until 40).map { i ->
                when {
                    i < 20 -> 100.0 + i * 0.1
                    i < 25 -> 102.0 - (i - 19) * 2.0
                    else -> 95.0 + (i - 24) * 0.5
                }
            }
        val candles =
            closes.mapIndexed { i, c ->
                Candle(
                    "BACKTEST:XAUUSD",
                    Money.of(c.toString()),
                    Money.of((c + 0.5).toString()),
                    Money.of((c - 0.5).toString()),
                    Money.of(c.toString()),
                    Money.of("1"),
                    i * 60_000L,
                    (i + 1) * 60_000L,
                )
            }
        source.seedBars("BACKTEST:XAUUSD", TimeWindow.ONE_MINUTE, candles)

        val strat =
            compile(
                """
                STRATEGY reentry_repro VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 0 AND POSITION.gold == 0
                  THEN BUY gold BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 50.0 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("reentry_repro" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(40 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        val entries = result.trades.filter { it.trade.side == com.qkt.common.Side.BUY }
        val exits = result.trades.filter { it.trade.side == com.qkt.common.Side.SELL }
        // The stall claim predicts entries.size == 1 (nothing after the SL exit).
        assertThat(exits).isNotEmpty
        assertThat(entries.size).isGreaterThanOrEqualTo(2)
    }
}
