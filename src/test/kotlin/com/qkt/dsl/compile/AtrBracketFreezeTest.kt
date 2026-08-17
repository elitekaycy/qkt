package com.qkt.dsl.compile

import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1025 — an indicator expression as a bracket `BY` distance is evaluated once at entry and
 * frozen into the request's SL/TP ASTs as literal arithmetic, so OrderManager's fill-time
 * re-anchor (which only understands literals) can re-anchor it to the actual fill price.
 */
class AtrBracketFreezeTest {
    private fun compile(src: String): Strategy =
        when (val r = Dsl.parse(src)) {
            is ParseResult.Success -> AstCompiler().compile(r.value)
            is ParseResult.Failure ->
                error("parse failed: ${r.errors.joinToString("\n") { "${it.line}:${it.col} ${it.message}" }}")
        }

    private fun emitBrackets(strategy: Strategy): List<OrderRequest.Bracket> {
        val ctx = testStrategyContext()
        val captured = mutableListOf<Signal>()
        // Constant true range of 2 per bar: high-low = 2, |high-prevClose| = 2 -> ATR(14) = 2.
        repeat(20) { i ->
            val px = BigDecimal(2000 + i)
            val start = i * 60_000L
            val c =
                Candle(
                    "BACKTEST:XAUUSD",
                    px,
                    px.add(BigDecimal.ONE),
                    px.subtract(BigDecimal.ONE),
                    px,
                    BigDecimal.ZERO,
                    start,
                    start + 60_000L,
                )
            strategy.onCandle(c, ctx, captured::add)
        }
        return captured.filterIsInstance<Signal.Submit>().map { it.request }.filterIsInstance<OrderRequest.Bracket>()
    }

    @Test
    fun `atr BY distance freezes to literal arithmetic at entry`() {
        val strategy =
            compile(
                """
                STRATEGY atr_bracket VERSION 1
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 0
                  THEN BUY gold SIZING 1 BRACKET { STOP LOSS BY atr(gold.candle, 14) * 2.0, TAKE PROFIT BY atr(gold.candle, 14) * 3.0 }
                """.trimIndent(),
            )
        val brackets = emitBrackets(strategy)
        assertThat(brackets).isNotEmpty
        val br = brackets.first()

        val slDistance = (br.stopLossAst as ChildBy).distance as BinaryOp
        val slAtr = slDistance.lhs as NumLit
        assertThat(slAtr.value).isEqualByComparingTo("2")
        assertThat((slDistance.rhs as NumLit).value).isEqualByComparingTo("2.0")
        val tpDistance = (br.takeProfitAst as ChildBy).distance as BinaryOp
        assertThat((tpDistance.lhs as NumLit).value).isEqualByComparingTo("2")
        assertThat((tpDistance.rhs as NumLit).value).isEqualByComparingTo("3.0")

        // Signal-time prices agree with the frozen ASTs: entry close, SL -2*ATR, TP +3*ATR.
        val entry = br.entry as OrderRequest.Market
        assertThat(entry.symbol).isEqualTo("BACKTEST:XAUUSD")
        val stop = br.stopLoss as StopLossSpec.Fixed
        assertThat(br.takeProfit.subtract(stop.price)).isEqualByComparingTo("10")
    }

    @Test
    fun `literal BY distance passes through unfrozen`() {
        val strategy =
            compile(
                """
                STRATEGY literal_bracket VERSION 1
                SYMBOLS
                  gold = BACKTEST:XAUUSD EVERY 1m
                RULES
                  WHEN gold.close > 0
                  THEN BUY gold SIZING 1 BRACKET { STOP LOSS BY 5, TAKE PROFIT BY 15 }
                """.trimIndent(),
            )
        val br = emitBrackets(strategy).first()
        assertThat(((br.stopLossAst as ChildBy).distance as NumLit).value).isEqualByComparingTo("5")
        assertThat(((br.takeProfitAst as ChildBy).distance as NumLit).value).isEqualByComparingTo("15")
    }
}
