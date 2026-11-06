package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A compiled strategy driven by raw candles (no candle hub) keeps rolling snapshots, `@OPEN`
 * snapshots and `SINCE OPEN` aggregates in step with the strategy's position.
 */
class UnboundCandleStateTest {
    private val held = mutableMapOf<String, BigDecimal>()

    private val positions =
        object : StrategyPositionView {
            override fun positionFor(symbol: String): Position? = held[symbol]?.let { Position(symbol, it, PRICE) }

            override fun allPositions(): Map<String, Position> = held.mapValues { Position(it.key, it.value, PRICE) }
        }

    private val ctx = testStrategyContext(positions = positions)

    private fun compile(rules: String) =
        AstCompiler().compile(
            (
                Dsl.parse(
                    "STRATEGY u VERSION 1\nSYMBOLS\n    b = BACKTEST:BTCUSDT EVERY 1m\n$rules\n",
                ) as ParseResult.Success
            ).value,
        )

    private fun candle(
        i: Int,
        close: String,
        high: String = close,
    ): Candle {
        val c = BigDecimal(close)
        return Candle("BACKTEST:BTCUSDT", c, BigDecimal(high), c, c, BigDecimal.ONE, i * 60_000L, (i + 1) * 60_000L - 1)
    }

    /** Feeds one candle per entry with the given held quantity; returns the signal kinds emitted per bar. */
    private fun run(
        strategy: Strategy,
        bars: List<Pair<Candle, String>>,
    ): List<List<String>> =
        bars.map { (candle, qty) ->
            held["BACKTEST:BTCUSDT"] = BigDecimal(qty)
            val out = mutableListOf<String>()
            strategy.onCandle(candle, ctx) { s: Signal -> out += s::class.simpleName.orEmpty() }
            out
        }

    @Test
    fun `a rolling LET snapshot compares against the value two bars back`() {
        val strategy = compile("LET m = b.close\nRULES\n    WHEN m@T-2 < m THEN BUY b SIZING 1")

        val bars = listOf(candle(0, "10") to "0", candle(1, "11") to "0", candle(2, "12") to "0", candle(3, "9") to "0")
        val fired = run(strategy, bars)

        assertThat(fired).containsExactly(emptyList(), emptyList(), listOf("Buy"), emptyList())
    }

    @Test
    fun `an OPEN snapshot is cleared when the position closes`() {
        val src = "LET e = b.close\nRULES\n    WHEN POSITION.b = 0 AND b.close > 100 THEN BUY b SIZING 1\n" +
            "    WHEN b.close < e@OPEN - 5 THEN SELL b SIZING 1"

        val stillOpen = run(compile(src), listOf(candle(0, "101") to "0", candle(1, "100") to "1", candle(2, "90") to "1"))
        val closed = run(compile(src), listOf(candle(0, "101") to "0", candle(1, "100") to "1", candle(2, "90") to "0"))

        assertThat(stillOpen).containsExactly(listOf("Buy"), emptyList(), listOf("Sell"))
        assertThat(closed).containsExactly(listOf("Buy"), emptyList(), emptyList())
    }

    @Test
    fun `a SINCE OPEN maximum ignores flat bars and restarts after the position closes`() {
        val src = "RULES\n    WHEN max(b.high) SINCE OPEN - b.close >= 5 THEN SELL b SIZING 1"

        val fired =
            run(
                compile(src),
                listOf(
                    candle(0, "100", high = "200") to "0",
                    candle(1, "108", high = "110") to "1",
                    candle(2, "104", high = "105") to "1",
                    candle(3, "100") to "0",
                    candle(4, "100") to "1",
                    candle(5, "101") to "1",
                ),
            )

        assertThat(fired).containsExactly(emptyList(), emptyList(), listOf("Sell"), emptyList(), emptyList(), emptyList())
    }

    @Test
    fun `a rolling-window aggregate updates on every bar regardless of position`() {
        val src = "RULES\n    WHEN sum(b.close) SINCE T-2 > 25 THEN BUY b SIZING 1"

        val fired = run(compile(src), listOf(candle(0, "10") to "0", candle(1, "10") to "0", candle(2, "20") to "1"))

        assertThat(fired).containsExactly(emptyList(), emptyList(), listOf("Buy"))
    }

    @Test
    fun `a hub-driven backtest keeps the same rolling LET snapshot`() {
        val strategy = compile("LET m = b.close\nRULES\n    WHEN m@T-2 < m AND POSITION.b = 0 THEN BUY b SIZING 1")
        val ticks =
            listOf("10", "11", "12", "9", "9").mapIndexed { i, p ->
                Tick("BACKTEST:BTCUSDT", Money.of(p), i * 60_000L + 30_000L)
            }

        val result =
            Backtest(
                strategies = listOf("u" to strategy),
                ticks = ticks,
                candleWindow = TimeWindow.parse("1m"),
                startingBalance = Money.of("10000"),
            ).run()

        assertThat(result.trades.map { it.trade.side }).containsExactly(Side.BUY)
        assertThat(result.trades.single().trade.timestamp).isGreaterThanOrEqualTo(3 * 60_000L)
    }

    private companion object {
        val PRICE = BigDecimal("100")
    }
}
