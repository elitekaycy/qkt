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
 * Under a HEDGING venue model (#1071) an opposite-direction entry does not net the
 * existing position away — both book their own coexisting legs (the retail-MT5 ticket
 * semantic), each protected and closed by its own bracket exits. The same tape under
 * NETTING is pinned by [StaleBracketExitAfterReversalTest].
 */
class HedgingModeBacktestTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `an opposite entry books its own leg and each bracket's exits close only that leg`() {
        val source = InMemoryMarketSource()
        // Same tape as the netting regression: 20: BUY bracket (~100.6, SL 99.1);
        // 21..27 fall to ~99.5 -> SELL 1 opens a SHORT leg (no netting);
        // 28..33 fall through 99.1 -> the LONG leg's SL closes the long leg only;
        // 34..39 flat drift.
        val closes =
            (0 until 40).map { i ->
                when {
                    i < 20 -> 99.8 + i * 0.01
                    i == 20 -> 100.6
                    i < 28 -> 100.6 - (i - 20) * 0.16
                    i < 34 -> 99.4 - (i - 27) * 0.1
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
                STRATEGY hedging_repro VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 100.5 AND POSITION.gold.count == 0
                  THEN BUY gold BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 50.0 }

                  WHEN gold.close < 99.6 AND POSITION.gold.count == 1
                  THEN SELL gold BRACKET { STOP LOSS BY 1.5, TAKE PROFIT BY 50.0 }
                """.trimIndent(),
            )

        val result =
            Backtest
                .fromSource(
                    strategies = listOf("hedging_repro" to strat),
                    source = source,
                    request =
                        MarketRequest(
                            symbols = listOf("BACKTEST:XAUUSD"),
                            from = Instant.ofEpochMilli(0L),
                            to = Instant.ofEpochMilli(40 * 60_000L),
                        ),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    executionConfig =
                        ExecutionSimulationConfig(
                            positionMode = com.qkt.broker.PositionAccountingMode.HEDGING,
                        ),
                ).run()

        fun qty(p: com.qkt.positions.Position?): BigDecimal = p?.quantity ?: BigDecimal.ZERO

        // No fill ever nets a position through zero into the other sign: hedging books
        // coexisting legs, it never reverses.
        val reversed =
            result.trades.any {
                qty(it.strategyPositionBefore).signum() > 0 && qty(it.strategyPositionAfter).signum() < 0
            }
        assertThat(reversed).describedAs("hedging must not net-reverse").isFalse()

        // The SELL entry coexists with the long: some fill sees net 0 while legs are open
        // (long 1 + short 1), i.e. the book was genuinely hedged at that moment.
        val hedgedMoment =
            result.trades.any {
                qty(it.strategyPositionBefore).signum() > 0 && qty(it.strategyPositionAfter).signum() == 0
            }
        assertThat(hedgedMoment).describedAs("expected long+short legs to coexist (net 0)").isTrue()

        // The long leg's SL fires while hedged and closes ONLY the long leg: net goes
        // 0 -> -1 via a `-sl` order — a legitimate per-leg exit, not a naked entry.
        val longLegStopOut =
            result.trades.any {
                it.trade.orderId.endsWith("-sl") &&
                    qty(it.strategyPositionBefore).signum() == 0 &&
                    qty(it.strategyPositionAfter).signum() < 0
            }
        assertThat(longLegStopOut)
            .describedAs("the long leg's SL should close the long leg out of the hedge")
            .isTrue()

        // Leg-aware audit labels (#1071 Task 5): the SELL entry that opens the short leg
        // of the hedge must read OPEN_SHORT, not CLOSE_LONG, even though net went 1 -> 0.
        val shortLegOpen =
            result.trades.first {
                it.trade.side == com.qkt.common.Side.SELL &&
                    !it.trade.orderId.endsWith("-sl") &&
                    !it.trade.orderId.endsWith("-tp") &&
                    qty(it.strategyPositionBefore).signum() > 0
            }
        assertThat(
            com.qkt.backtest.report.TradeAuditSummaries
                .positionEffect(shortLegOpen),
        ).isEqualTo("OPEN_SHORT")
        assertThat(shortLegOpen.legAction)
            .isEqualTo(com.qkt.positions.StrategyPositionTracker.LegAction.OPENED)

        // Reduce-only invariant, leg-aware: no exit fill ever grows GROSS exposure.
        // (Net can legitimately move away from zero when one leg of a hedge closes.)
        val exitFills = result.trades.filter { it.trade.orderId.endsWith("-sl") || it.trade.orderId.endsWith("-tp") }
        assertThat(exitFills).isNotEmpty
        // Every exit fill must map to a leg close: realized PnL is leg-specific, so a
        // closing fill carries a non-null realized amount (zero only for an exact
        // entry-price touch, which this tape does not produce).
        assertThat(exitFills.all { it.realized.signum() != 0 })
            .describedAs("every exit fill realizes against its own leg")
            .isTrue()
    }
}
