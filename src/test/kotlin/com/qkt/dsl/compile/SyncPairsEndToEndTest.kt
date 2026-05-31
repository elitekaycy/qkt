package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — `SYNCHRONIZE` end-to-end against a deterministic two-symbol tick stream.
 *
 * Drives a `Backtest` through DSL → AstCompiler → CandleHub sync group → trade
 * emission. With `SYNCHRONIZE gold silver`, the rule evaluates once per matched
 * bar pair, so the rule's cross-stream predicate `gold.close > silver.close`
 * reads the same-window silver bar — not the previous window's value that the
 * per-stream wiring would see. Sister of the unit-level
 * `CandleHubSyncFireTest` and `AstCompilerSyncBindTest`.
 */
class SyncPairsEndToEndTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private fun ticks(stream: List<Triple<String, Long, String>>): List<Tick> =
        stream.map { (symbol, t, price) ->
            Tick(symbol = symbol, price = Money.of(price), timestamp = t)
        }

    /** Strategy: BUY gold once when `gold.close > silver.close`. Two streams. */
    private fun src(synchronize: Boolean): String =
        """
        STRATEGY pairs VERSION 1
        SYMBOLS
          gold   = BACKTEST:GOLD EVERY 1m,
          silver = BACKTEST:SILVER EVERY 1m
          ${if (synchronize) "SYNCHRONIZE gold silver" else ""}
        RULES
          WHEN gold.close > silver.close AND POSITION.gold = 0
          THEN BUY gold SIZING 0.1
        """.trimIndent()

    /**
     * Interleave gold and silver ticks so each 1m bar closes for both symbols.
     * End times line up at 60_000, 120_000, 180_000. Prices ascend; gold always
     * above silver, so the predicate is true every window.
     */
    private val sample =
        ticks(
            listOf(
                Triple("BACKTEST:GOLD", 0L, "100"),
                Triple("BACKTEST:SILVER", 0L, "50"),
                Triple("BACKTEST:GOLD", 60_000L, "101"),
                Triple("BACKTEST:SILVER", 60_000L, "51"),
                Triple("BACKTEST:GOLD", 120_000L, "102"),
                Triple("BACKTEST:SILVER", 120_000L, "52"),
                Triple("BACKTEST:GOLD", 180_000L, "103"),
                Triple("BACKTEST:SILVER", 180_000L, "53"),
            ),
        )

    @Test
    fun `SYNCHRONIZE fires the rule once per matched bar pair`() {
        val result =
            Backtest(
                strategies = listOf("pairs" to compile(src(synchronize = true))),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(1)
    }

    @Test
    fun `without SYNCHRONIZE the rule still runs end-to-end with no regression`() {
        val result =
            Backtest(
                strategies = listOf("pairs" to compile(src(synchronize = false))),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).isNotEmpty
    }
}
