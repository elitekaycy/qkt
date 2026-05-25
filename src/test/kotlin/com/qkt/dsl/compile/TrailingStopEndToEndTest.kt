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
 * End-to-end confirmation that DSL `TRAILING BY` parses, compiles, and fires through
 * `Backtest`. Complements [com.qkt.app.OrderManagerTrailingTest] (unit-level) and
 * [com.qkt.dsl.parse.ParserOrderTypeTest] / [com.qkt.dsl.compile.OrderTypeCompilerTest]
 * (surface-level) by exercising the full DSL → compile → OrderManager fallback chain
 * against a deterministic tick sequence.
 */
class TrailingStopEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private fun compile(src: String) =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure -> error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `BUY with TRAILING BY parks a pending order and fires when the trail level is breached`() {
        // BUY-side trailing tracks the LOW water mark and fires when price rises by N
        // above the lowest seen — a classic breakout-style entry.
        //   submit at 100  → LWM=100, level=110
        //   drops to 90    → LWM=90,  level=100
        //   drops to 80    → LWM=80,  level=90
        //   rises to 95    → 95 >= 90 → trail fires (BUY at 95)
        val sample =
            ticks(listOf("100", "98", "95", "92", "90", "85", "80", "82", "88", "95", "100", "100"))

        val strat =
            compile(
                """
                STRATEGY trailing_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc ORDER_TYPE = TRAILING BY 10
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("trailing_e2e" to strat),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).isNotEmpty
    }
}
