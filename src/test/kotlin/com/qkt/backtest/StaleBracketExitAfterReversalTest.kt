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
import java.math.BigDecimal
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A bracket's protective exits exist to reduce the position they protect (#1069). When an
 * opposite-direction entry consumes that position (netting reversal), the venue drops the
 * position's SL/TP with it — the engine must do the same. A stale exit left working fires
 * later as a naked opposite-direction fill: an order named `-sl` INCREASES exposure, with
 * no protection of its own.
 */
class StaleBracketExitAfterReversalTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `a reversal retires the consumed bracket's exits instead of leaving them to fire naked`() {
        val source = InMemoryMarketSource()
        // 0..19 warmup drift below the long trigger; 20: cross 100.5 -> BUY bracket
        // (SL BY 1.5 => ~99.1); 21..27 fall to ~99.5 -> SELL 2 reverses to short 1 while
        // the old long's SL (99.1) is untouched; 28..33 keep falling through 99.1 where
        // the stale SL would fire as a naked SELL; 34..39 drift flat.
        val closes =
            (0 until 40).map { i ->
                when {
                    i < 20 -> 99.8 + i * 0.01
                    i == 20 -> 100.6
                    i < 28 -> 100.6 - (i - 20) * 0.16 // 21..27: 100.44 .. 99.48
                    i < 34 -> 99.4 - (i - 27) * 0.1 // 28..33: 99.3 .. 98.8 (through 99.1)
                    else -> 98.8
                }
            }
        val candles =
            closes.mapIndexed { i, c ->
                Candle(
                    "BACKTEST:XAUUSD",
                    Money.of(c.toString()),
                    Money.of((c + 0.05).toString()),
                    Money.of((c - 0.05).toString()),
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
                STRATEGY reversal_repro VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 100.5 AND POSITION.gold == 0
                  THEN BUY gold BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 50.0 }

                  WHEN gold.close < 99.6 AND POSITION.gold > 0
                  THEN SELL gold SIZING 2 BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 50.0 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("reversal_repro" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(40 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                ).run()

        fun qty(p: com.qkt.positions.Position?): BigDecimal = p?.quantity ?: BigDecimal.ZERO

        // Sanity: the reversal actually happened (long -> short via the SELL 2 entry).
        val reversed =
            result.trades.any {
                qty(it.strategyPositionBefore).signum() > 0 && qty(it.strategyPositionAfter).signum() < 0
            }
        assertThat(reversed).describedAs("expected the SELL 2 entry to net the long into a short").isTrue()

        // The invariant under test: no protective-exit fill (`-sl`/`-tp` order id) may ever
        // INCREASE absolute strategy exposure. A stale exit surviving the reversal does.
        val nakedExitFills =
            result.trades.filter {
                (it.trade.orderId.endsWith("-sl") || it.trade.orderId.endsWith("-tp")) &&
                    qty(it.strategyPositionAfter).abs() > qty(it.strategyPositionBefore).abs()
            }
        assertThat(nakedExitFills)
            .describedAs(
                "stale bracket exits fired as naked entries: %s",
                nakedExitFills.map { "${it.trade.orderId} ${it.trade.side} ${it.trade.quantity}" },
            ).isEmpty()
    }
}
