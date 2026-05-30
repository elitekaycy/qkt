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
 * End-to-end confirmation that `STOP LOSS TRAILING <d> AFTER MFE >= <t>` parses,
 * compiles, and fires through Backtest. Complements [ParserArmedTrailingStopTest]
 * (parser), [ChildPriceResolverArmedTrailTest] (compiler), and
 * [com.qkt.execution.ArmedTrailingStopTest] (value type) by exercising the full
 * DSL → AstCompiler → OrderManager bracket-fallback chain against a deterministic
 * tick sequence. See #48 and the phase-36 spec.
 */
class ArmedTrailEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private fun compile(src: String) =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error(
                    "parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}",
                )
        }

    private val strategySrc =
        """
        STRATEGY armed_e2e VERSION 1
        DEFAULTS { SIZING = 1 TIF = GTC }
        SYMBOLS
          btc = BACKTEST:BTCUSDT EVERY 1m
        RULES
          WHEN btc.close = 100 AND POSITION.btc = 0
          THEN BUY btc BRACKET { STOP LOSS TRAILING 5 AFTER MFE >= 10, TAKE PROFIT BY 50 }
        """.trimIndent()

    @Test
    fun `never-armed bracket exits at the pre-arm fixed stop`() {
        // Strategy condition `btc.close = 100` fires on the candle-close at tick 1's
        // arrival; lastObservedPrice = 102 by the time the BUY bracket is submitted,
        // so the ArmedTrailingStop's entryPrice anchor = 102 and pre-arm stop = 97.
        // Ticks keep hwm at 102 (max), MFE = 0 — never arms.
        // Tick 2 (96) < 97 — trigger fires; trailing padding lets the fill materialise.
        val sample = ticks(listOf("100", "102", "96", "96", "96", "96", "96"))

        val result =
            Backtest(
                strategies = listOf("armed_e2e" to compile(strategySrc)),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val sells = result.trades.filter { it.trade.side == Side.SELL }
        assertThat(sells).isNotEmpty
        // Exit at or below the pre-arm stop level (97).
        assertThat(sells.first().trade.price).isLessThanOrEqualTo(Money.of("97"))
    }

    @Test
    fun `armed bracket exits at the trailing post-arm level after MFE crosses the threshold`() {
        // Anchor: entryPrice = 102 (lastObservedPrice at submit), distance = 5, threshold = 10.
        // Tick 2 (108): hwm = 108, MFE = 6 — not armed.
        // Tick 3 (112): hwm = 112, MFE = 10 — ARMS. Trail level = 112 - 5 = 107.
        // Tick 4 (108): above 107, no trigger.
        // Tick 5 (106): ≤ 107, trigger fires. Padding ticks let the fill materialise.
        val sample = ticks(listOf("100", "102", "108", "112", "108", "106", "106", "106"))

        val result =
            Backtest(
                strategies = listOf("armed_e2e" to compile(strategySrc)),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val sells = result.trades.filter { it.trade.side == Side.SELL }
        assertThat(sells).isNotEmpty
        // Above the pre-arm stop (97) — the trail moved up.
        assertThat(sells.first().trade.price).isGreaterThan(Money.of("97"))
        // At or below the post-arm trail level (hwm=112 - 5 = 107).
        assertThat(sells.first().trade.price).isLessThanOrEqualTo(Money.of("107"))
    }
}
