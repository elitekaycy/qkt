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

class StopRatchetEndToEndTest {
    private fun compile(source: String) =
        when (val result = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(result.value)
            is ParseResult.Failure -> error(result.errors.joinToString { it.message })
        }

    private fun ticks(prices: List<String>): List<Tick> =
        prices.mapIndexed { index, price ->
            Tick("BACKTEST:BTCUSDT", Money.of(price), index * 60_000L)
        }

    @Test
    fun `stepped stop parses compiles and exits through the paper pipeline`() {
        val strategy =
            compile(
                """
                STRATEGY stepped_e2e VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close = 100 AND POSITION.btc = 0
                  THEN BUY btc BRACKET {
                    STOP LOSS BY 5
                      STEP TO BREAKEVEN AFTER MFE >= 10
                      STEP TO ENTRY + 5 AFTER MFE >= 15,
                    TAKE PROFIT BY 50
                  }
                """.trimIndent(),
            )

        val result =
            Backtest(
                strategies = listOf("stepped_e2e" to strategy),
                ticks = ticks(listOf("100", "102", "112", "117", "111", "106", "106", "106")),
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        val exit = result.trades.first { it.trade.side == Side.SELL }
        assertThat(exit.trade.price).isGreaterThan(Money.of("97"))
        assertThat(exit.trade.price).isLessThanOrEqualTo(Money.of("107"))
    }

    @Test
    fun `fixed bracket strategy remains bit identical across replays`() {
        val strategySource =
            """
            STRATEGY fixed_parity VERSION 1
            DEFAULTS { SIZING = 1 TIF = GTC }
            SYMBOLS btc = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN btc.close = 100 AND POSITION.btc = 0
              THEN BUY btc BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }
            """.trimIndent()
        val input = ticks(listOf("100", "102", "108", "112", "112", "112"))

        val first =
            Backtest(
                strategies = listOf("fixed_parity" to compile(strategySource)),
                ticks = input,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()
        val second =
            Backtest(
                strategies = listOf("fixed_parity" to compile(strategySource)),
                ticks = input,
                candleWindow = TimeWindow.ONE_MINUTE,
            ).run()

        assertThat(second).isEqualTo(first)
    }
}
