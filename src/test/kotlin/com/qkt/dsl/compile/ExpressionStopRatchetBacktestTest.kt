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

/** A bracket ratchet sized from an expression trades end to end, and moves the stop to breakeven (#1116). */
class ExpressionStopRatchetBacktestTest {
    private fun compile(src: String) = AstCompiler().compile((Dsl.parse(src) as ParseResult.Success).value)

    private fun tick(
        t: Long,
        price: String,
    ) = Tick("BACKTEST:BTCUSDT", Money.of(price), t)

    @Test
    fun `STEP TO BREAKEVEN with an expression threshold arms and exits at entry`() {
        // Stop = 10% of the close, breakeven after MFE of 5% of the close. Entry at 100 on the
        // first bar close; price runs to 106 (MFE 6 > 5, stop moves to 100), then falls back.
        val src =
            """
            STRATEGY ratchet_expr VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN POSITION.b = 0 AND TRADES.today = 0
              THEN BUY b SIZING 1 BRACKET {
                STOP LOSS BY b.close * 0.10 STEP TO BREAKEVEN AFTER MFE >= b.close * 0.05,
                TAKE PROFIT BY 50
              }
            """.trimIndent()
        val ticks =
            listOf(
                tick(0L, "100"),
                tick(60_000L, "100"),
                tick(61_000L, "103"),
                tick(62_000L, "106"),
                tick(63_000L, "102"),
                tick(64_000L, "99.5"),
                tick(65_000L, "95"),
            )
        val result =
            Backtest(
                strategies = listOf("ratchet_expr" to compile(src)),
                ticks = ticks,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val trades = result.trades.map { it.trade }
        assertThat(trades.map { it.side }).containsExactly(Side.BUY, Side.SELL)
        assertThat(trades[0].price).isEqualByComparingTo("100")
        // Exited on the breakeven stop at the first print at or below 100, not the initial 90 stop.
        assertThat(trades[1].price).isEqualByComparingTo("99.5")
    }

    @Test
    fun `a STACK bracket with an expression ratchet is rejected at compile time`() {
        val src =
            """
            STRATEGY stack_expr VERSION 1
            SYMBOLS
              b = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN POSITION.b = 0
              THEN BUY b SIZING 1
                STACK 3 SPACING 200 ABOVE WITHIN 4h
                BRACKET {
                  STOP LOSS BY b.close * 0.10 STEP TO BREAKEVEN AFTER MFE >= 5,
                  TAKE PROFIT BY 50
                }
            """.trimIndent()
        val parsed = Dsl.parse(src)
        assertThat(parsed).isInstanceOf(ParseResult.Success::class.java)
        assertThatThrownBy { AstCompiler().compile((parsed as ParseResult.Success).value) }
            .hasMessageContaining("numeric literal in a STACK bracket")
    }
}
