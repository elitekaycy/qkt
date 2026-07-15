package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.kotlin.and
import com.qkt.dsl.kotlin.bd
import com.qkt.dsl.kotlin.bracket
import com.qkt.dsl.kotlin.childBy
import com.qkt.dsl.kotlin.childRr
import com.qkt.dsl.kotlin.eq
import com.qkt.dsl.kotlin.gt
import com.qkt.dsl.kotlin.limitAt
import com.qkt.dsl.kotlin.position
import com.qkt.dsl.kotlin.riskAbs
import com.qkt.dsl.kotlin.strategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.Candle
import com.qkt.pnl.StrategyPnLView
import com.qkt.strategy.Signal
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.junit.jupiter.api.Test

class OrderSurfaceEndToEndTest {
    private fun compile(source: String): DslCompiledStrategy =
        when (val parsed = Dsl.parse(source)) {
            is ParseResult.Success -> AstCompiler().compile(parsed.value) as DslCompiledStrategy
            is ParseResult.Failure ->
                error(parsed.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" })
        }

    private fun candle(close: String): Candle =
        Candle(
            "BACKTEST:BTCUSDT",
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal(close),
            BigDecimal.ZERO,
            0L,
            60_000L,
        )

    private fun fundedContext() =
        testStrategyContext(
            pnl =
                object : StrategyPnLView {
                    override fun realized(): BigDecimal = BigDecimal.ZERO

                    override fun unrealizedFor(symbol: String): BigDecimal = BigDecimal.ZERO

                    override fun unrealizedTotal(): BigDecimal = BigDecimal.ZERO

                    override fun total(): BigDecimal = BigDecimal.ZERO

                    override fun equity(): BigDecimal = BigDecimal("10000")

                    override fun balance(): BigDecimal = BigDecimal("10000")
                },
        )

    @Test
    fun `LIMIT entry with BRACKET emits Submit Bracket signal with correct prices`() {
        val ast =
            strategy("limit_bracket", version = 1) {
                val btc = stream("btc", broker = "BACKTEST", symbol = "BTCUSDT", every = "1m")
                rule {
                    whenever((btc.close gt 105.bd) and (position(btc) eq 0.bd))
                    then {
                        buy(
                            stream = btc,
                            sizing = riskAbs(50.bd),
                            orderType = limitAt(99.bd),
                            bracket =
                                bracket(
                                    stopLoss = childBy(5.bd),
                                    takeProfit = childRr(3.bd),
                                ),
                        )
                    }
                }
            }
        val strategy = AstCompiler().compile(ast)

        val captured = mutableListOf<Signal>()
        val ctx = testStrategyContext()
        val c =
            Candle(
                "BACKTEST:BTCUSDT",
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal("110"),
                BigDecimal.ZERO,
                0L,
                60_000L,
            )
        strategy.onCandle(c, ctx, captured::add)

        val submits = captured.filterIsInstance<Signal.Submit>()
        assertThat(submits).isNotEmpty
        val bracketSig = submits.first { it.request is OrderRequest.Bracket }
        val br = bracketSig.request as OrderRequest.Bracket
        // entry = limit at 99 (fixed), stop loss BY 5 → 94, take profit RR 3 → 99 + 3*5 = 114
        assertThat((br.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("94")
        assertThat(br.takeProfit).isEqualByComparingTo("114")
        assertThat(br.entry).isInstanceOf(OrderRequest.Limit::class.java)
        assertThat(br.side).isEqualTo(Side.BUY)
        // RISK $50 / stop distance 5 = qty 10
        assertThat(br.quantity).isEqualByComparingTo("10")
    }

    @Test
    fun `undefined pending order price skips order and later numeric edge submits`() {
        val strategy =
            compile(
                """
                STRATEGY undefined_limit VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close >= 100
                  THEN BUY btc ORDER_TYPE = LIMIT AT
                    CASE WHEN btc.close > 100 THEN btc.close - 1 ELSE ACCOUNT.last_trade_pnl END
                """.trimIndent(),
            )
        val ctx = testStrategyContext()
        val captured = mutableListOf<Signal>()

        strategy.onCandle(candle("100"), ctx, captured::add)
        assertThat(captured).isEmpty()

        strategy.onCandle(candle("99"), ctx, captured::add)
        assertThat(captured).isEmpty()

        strategy.onCandle(candle("101"), ctx, captured::add)
        val req = captured.single() as Signal.Submit
        val limit = req.request as OrderRequest.Limit
        assertThat(limit.limitPrice).isEqualByComparingTo("100")
    }

    @Test
    fun `undefined bracket child price skips order and later numeric edge submits`() {
        val strategy =
            compile(
                """
                STRATEGY undefined_bracket_child VERSION 1
                DEFAULTS { SIZING = 1 TIF = GTC }
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                RULES
                  WHEN btc.close >= 100
                  THEN BUY btc BRACKET {
                    STOP LOSS BY CASE WHEN btc.close > 100 THEN 5 ELSE ACCOUNT.last_trade_pnl END,
                    TAKE PROFIT BY 10
                  }
                """.trimIndent(),
            )
        val ctx = testStrategyContext()
        val captured = mutableListOf<Signal>()

        strategy.onCandle(candle("100"), ctx, captured::add)
        assertThat(captured).isEmpty()

        strategy.onCandle(candle("99"), ctx, captured::add)
        assertThat(captured).isEmpty()

        strategy.onCandle(candle("101"), ctx, captured::add)
        val req = captured.single() as Signal.Submit
        val bracket = req.request as OrderRequest.Bracket
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("96")
        assertThat(bracket.takeProfit).isEqualByComparingTo("111")
    }

    @Test
    fun `risk sizing uses runtime AT expression BY expression and PCT stop distances`() {
        val stopForms =
            listOf(
                "AT btc.close - 5",
                "BY btc.close / 20",
                "PCT 5",
            )

        for (stopForm in stopForms) {
            val strategy =
                compile(
                    """
                    STRATEGY runtime_risk VERSION 1
                    SYMBOLS
                      btc = BACKTEST:BTCUSDT EVERY 1m
                    RULES
                      WHEN btc.close > 0
                      THEN BUY btc SIZING 1 PCT RISK BRACKET {
                        STOP LOSS $stopForm,
                        TAKE PROFIT RR 2
                      }
                    """.trimIndent(),
                )
            val captured = mutableListOf<Signal>()

            strategy.onCandle(candle("100"), fundedContext(), captured::add)

            val bracket = (captured.single() as Signal.Submit).request as OrderRequest.Bracket
            assertThat(bracket.quantity).describedAs(stopForm).isEqualByComparingTo("20")
            assertThat((bracket.stopLoss as StopLossSpec.Fixed).price)
                .describedAs(stopForm)
                .isEqualByComparingTo("95")
            assertThat(bracket.takeProfit).describedAs(stopForm).isEqualByComparingTo("110")
        }
    }

    @Test
    fun `documented ATR stop compiles with percent risk sizing`() {
        assertThatCode {
            compile(
                """
                STRATEGY atr_risk VERSION 1
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m WARMUP 20 BARS
                RULES
                  WHEN btc.close > 0
                  THEN BUY btc SIZING 0.5 PCT RISK BRACKET {
                    STOP LOSS AT btc.close - atr(btc, 14) * 2,
                    TAKE PROFIT RR 3
                  }
                """.trimIndent(),
            )
        }.doesNotThrowAnyException()
    }

    @Test
    fun `computed CASE risk sizing resolves once into a bracket quantity`() {
        val strategy =
            compile(
                """
                STRATEGY computed_bracket_sizing VERSION 1
                SYMBOLS
                  btc = BACKTEST:BTCUSDT EVERY 1m
                LET conviction = CASE WHEN btc.close >= 100 THEN 1.5 ELSE 1 END
                RULES
                  WHEN btc.close > 0
                  THEN BUY btc SIZING RISK ${'$'} (100 * conviction) BRACKET {
                    STOP LOSS BY 5,
                    TAKE PROFIT BY 10
                  }
                """.trimIndent(),
            )
        val captured = mutableListOf<Signal>()

        strategy.onCandle(candle("100"), fundedContext(), captured::add)

        val bracket = (captured.single() as Signal.Submit).request as OrderRequest.Bracket
        assertThat(bracket.quantity).isEqualByComparingTo("30")
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("95")
        assertThat(bracket.takeProfit).isEqualByComparingTo("110")
    }
}
