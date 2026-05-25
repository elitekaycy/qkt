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
 * End-to-end: a DSL strategy using `vwap(btc.tick, N)` warms from tick-level data
 * (not candle aggregates) and fires a BUY when close crosses above VWAP.
 *
 * Complements [com.qkt.dsl.stdlib.IndicatorRegistryTest] (catalog entry) and
 * exercises the tick-fed indicator path: TradingPipeline → CompiledStrategy.onTick
 * → IndicatorBinding.updateFromTick → VWAP update → condition evaluation at candle close.
 */
class VwapEndToEndTest {
    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { i, p ->
            Tick(
                symbol = "BACKTEST:BTCUSDT",
                price = Money.of(p),
                timestamp = i * 60_000L,
                volume = BigDecimal.ONE,
            )
        }

    private fun compile(src: String) =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    @Test
    fun `vwap on tick stream fires a BUY when close exceeds the rolling vwap`() {
        // VWAP(3) with constant volume = arithmetic mean of last 3 prices. After 3 ticks
        // at 100, 101, 102 the VWAP reads ~101 — close is 102 → BUY fires on candle close.
        val sample =
            ticks(listOf("100", "100", "101", "102", "102", "102", "102", "102", "102"))

        val strat =
            compile(
                """
                STRATEGY vwap_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close > vwap(btc.tick, 3) AND POSITION.btc = 0
                  THEN BUY btc
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("vwap_e2e" to strat),
                ticks = sample,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).isNotEmpty
    }
}
