package com.qkt.dsl.compile

import com.qkt.backtest.Backtest
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * End-to-end confirmation that `RESIZE` grows, shrinks, and flattens a live position through the
 * full DSL → compile → engine → position-tracker chain, and that a protective stop tracks the
 * resized quantity (the layer-3 sync).
 */
class ResizeBacktestTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(symbol = "BACKTEST:BTCUSDT", price = Money.of(p), timestamp = i * 60_000L)
        }

    private fun compile(src: String) =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun run(
        name: String,
        src: String,
        prices: List<String>,
    ) = Backtest(
        strategies = listOf(name to compile(src)),
        ticks = ticks(prices),
        candleWindow = TimeWindow.ONE_MINUTE,
    ).run()

    @Test
    fun `RESIZE grows, shrinks, and flattens the primary`() {
        val src =
            """
            STRATEGY resize_core VERSION 1
            DEFAULTS { SIZING = 0.01 TIF = GTC }
            SYMBOLS
              btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
              WHEN btc.close = 110 THEN RESIZE btc TO 0.03
              WHEN btc.close = 120 THEN RESIZE btc TO 0.01
              WHEN btc.close = 130 THEN RESIZE btc TO 0
            """.trimIndent()
        val result = run("resize_core", src, listOf("100", "110", "120", "130", "130"))

        val buys = result.trades.filter { it.trade.side == Side.BUY }.map { it.trade.quantity }
        val sells = result.trades.filter { it.trade.side == Side.SELL }.map { it.trade.quantity }
        val bought = buys.fold(BigDecimal.ZERO) { a, q -> a.add(q) }
        val sold = sells.fold(BigDecimal.ZERO) { a, q -> a.add(q) }
        // open 0.01 + grow 0.02 = 0.03 bought; shrink 0.02 + flatten 0.01 = 0.03 sold; net flat.
        assertThat(bought).isEqualByComparingTo("0.03")
        assertThat(sold).isEqualByComparingTo("0.03")
        // A grow (a second BUY beyond the 0.01 open) and a shrink (a SELL before the flatten) happened.
        assertThat(buys.any { it.compareTo(BigDecimal("0.02")) == 0 }).isTrue()
        assertThat(sells.any { it.compareTo(BigDecimal("0.02")) == 0 }).isTrue()
    }

    @Test
    fun `a CLOSE-rule stop closes the full resized position`() {
        // The synced-stop idiom: a CLOSE rule always closes the CURRENT position, so it tracks the
        // resize for free — no bracket needed. After growing to 0.03, the stop must close 0.03.
        val src =
            """
            STRATEGY resize_stop VERSION 1
            DEFAULTS { SIZING = 0.01 TIF = GTC }
            SYMBOLS
              btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close = 100 AND POSITION.btc = 0 THEN BUY btc
              WHEN btc.close = 110 THEN RESIZE btc TO 0.03
              WHEN btc.close = 90 THEN CLOSE btc
            """.trimIndent()
        val result = run("resize_stop", src, listOf("100", "110", "90", "90"))

        val sells = result.trades.filter { it.trade.side == Side.SELL }.map { it.trade.quantity }
        val bought =
            result.trades
                .filter { it.trade.side == Side.BUY }
                .fold(BigDecimal.ZERO) { a, t -> a.add(t.trade.quantity) }
        val sold = sells.fold(BigDecimal.ZERO) { a, q -> a.add(q) }
        // Open 0.01 + grow 0.02 = 0.03; the CLOSE sells the full resized 0.03, not the original 0.01.
        assertThat(bought).isEqualByComparingTo("0.03")
        assertThat(sold).isEqualByComparingTo("0.03")
        assertThat(sells.any { it.compareTo(BigDecimal("0.03")) == 0 }).isTrue()
    }
}
