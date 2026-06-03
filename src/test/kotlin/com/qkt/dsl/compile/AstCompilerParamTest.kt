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

class AstCompilerParamTest {
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
    fun `a PARAM in a DEFAULTS sizing clause drives the executed trade quantity`() {
        val strat =
            compile(
                """
                STRATEGY param_e2e VERSION 1
                DEFAULTS { SIZING = qty TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                PARAM qty = 2
                RULES
                  WHEN btc.close > 100 AND POSITION.btc = 0
                  THEN BUY btc
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("param_e2e" to strat),
                ticks = ticks(listOf("101", "101", "101")),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val buys = result.trades.filter { it.trade.side == Side.BUY }
        assertThat(buys).isNotEmpty
        assertThat(buys.first().trade.quantity).isEqualByComparingTo("2")
    }
}
