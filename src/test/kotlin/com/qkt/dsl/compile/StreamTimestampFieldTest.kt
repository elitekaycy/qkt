package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** `<stream>.timestamp` is the bar's start in epoch ms, with lookback, and not an indicator series (#1130). */
class StreamTimestampFieldTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private fun ticks() = (0..6).map { i -> Tick("BACKTEST:BTCUSDT", Money.of("100"), i * 60_000L + 30_000L) }

    @Test
    fun `timestamp is the bar start and timestamp lookback is the previous bar's start`() {
        // Buys once, on the bar that starts at 180000 whose previous bar starts at 120000.
        val src =
            """
            STRATEGY ts VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN b.timestamp = 180000 AND b.timestamp[1] = 120000 AND POSITION.b = 0
              THEN BUY b SIZING 1
            """.trimIndent()
        val result =
            Backtest(
                strategies = listOf("ts" to compile(src)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).hasSize(1)
        // The bar [180000, 240000) closes on the first tick of the next bar, stamped 270000.
        assertThat(buys.single().trade.timestamp).isEqualTo(270_000L)
    }

    @Test
    fun `timestamp is not a price series for indicators`() {
        val src =
            """
            STRATEGY ts VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN ema(b.timestamp, 3) > 0 THEN BUY b SIZING 1
            """.trimIndent()
        assertThatThrownBy {
            Backtest(
                strategies = listOf("ts" to compile(src)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        }.hasMessageContaining("series field must be numeric")
    }

    @Test
    fun `a LOG field may read an aggregate and the rolling shorthand`() {
        // Used to fail to compile: "Aggregate requires rule symbol context".
        val src =
            """
            STRATEGY log_agg VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1m
            LET up3 = count(b.close > b.open, 3)
            RULES
              WHEN b.close > 0
              THEN LOG "avg={a} up={u} since={m}" a=avg(b.close, 3) u=up3 m=mean(b.close) SINCE T-2
            """.trimIndent()
        val result =
            Backtest(
                strategies = listOf("log_agg" to compile(src)),
                ticks = ticks(),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        assertThat(result.trades).isEmpty()
    }
}
