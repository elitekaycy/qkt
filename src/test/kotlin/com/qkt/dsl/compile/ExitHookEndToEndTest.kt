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

class ExitHookEndToEndTest {
    @Test
    fun `stop-and-reverse hook traverses the shared paper pipeline`() {
        val strategy =
            compile(
                """
                STRATEGY sar VERSION 1
                SYMBOLS gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close = 100 AND POSITION.gold = 0
                  THEN BUY gold SIZING 1
                    BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 50 }
                    ON_STOP {
                      SELL gold SIZING EXIT.qty
                        BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 10 }
                    }
                """.trimIndent(),
            )

        val result = backtest(strategy, listOf("100", "100", "94", "94", "94"))
        assertThat(result.trades.map { it.trade.side })
            .startsWith(Side.BUY, Side.SELL, Side.SELL)
        assertThat(result.trades[2].trade.quantity).isEqualByComparingTo("1")
    }

    @Test
    fun `TP retrace hook fills before its GTD deadline`() {
        val strategy = compile(tpRetraceSource())

        val result = backtest(strategy, listOf("100", "100", "111", "110", "106", "106"))

        assertThat(result.trades.map { it.trade.side })
            .startsWith(Side.BUY, Side.SELL, Side.BUY)
        assertThat(result.trades[2].trade.price).isEqualByComparingTo("106")
    }

    @Test
    fun `TP retrace hook expires when price arrives after its GTD deadline`() {
        val strategy = compile(tpRetraceSource())

        val result = backtest(strategy, listOf("100", "100", "111", "110", "110", "110", "105", "105"))

        assertThat(result.trades.map { it.trade.side }).containsExactly(Side.BUY, Side.SELL)
    }

    private fun tpRetraceSource(): String =
        """
        STRATEGY retrace VERSION 1
        SYMBOLS gold = BACKTEST:XAUUSD EVERY 1m
        RULES
          WHEN gold.close = 100 AND POSITION.gold = 0
          THEN BUY gold SIZING 1
            BRACKET { STOP LOSS BY 20, TAKE PROFIT BY 10 }
            ON_TP {
              BUY gold SIZING EXIT.qty
                ORDER_TYPE = LIMIT WITH 5
                TIF GTD UNTIL NOW + 3m
            }
        """.trimIndent()

    private fun backtest(
        strategy: com.qkt.strategy.Strategy,
        prices: List<String>,
    ) = Backtest(
        strategies = listOf("hooks" to strategy),
        ticks =
            prices.mapIndexed { index, price ->
                Tick("BACKTEST:XAUUSD", Money.of(price), index * 60_000L)
            },
        candleWindow = TimeWindow.ONE_MINUTE,
    ).run()

    private fun compile(source: String): com.qkt.strategy.Strategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value)
            is ParseResult.Failure -> error(parsed.errors.joinToString { it.message })
        }
}
