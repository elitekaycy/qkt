package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.BreakOffset
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.Latch
import com.qkt.dsl.ast.LatchBracket
import com.qkt.dsl.ast.LatchEntry
import com.qkt.dsl.ast.LatchLimit
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SizeRiskAbs
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchCompilerTest {
    private fun limitLatch(): Latch =
        Latch(
            stream = "gold",
            sensor = BreakOffset(reference = null, offset = NumLit(BigDecimal("0.50"))),
            armWindow = DurationAst(300_000L),
            name = null,
            entries =
                listOf(
                    LatchEntry(
                        order = LatchLimit(DirRel(DirSense.AGAINST, NumLit(BigDecimal("4")))),
                        bracket =
                            LatchBracket(
                                stopLoss = DirRel(DirSense.AGAINST, NumLit(BigDecimal("12"))),
                                takeProfit = DirRel(DirSense.WITH, NumLit(BigDecimal("5"))),
                            ),
                        sizing = SizeRiskAbs(NumLit(BigDecimal("250"))),
                        expire = DurationAst(7_200_000L),
                    ),
                ),
        )

    @Test
    fun `long break builds a BUY bracketed limit below the anchor`() {
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(direction = 1, anchor = BigDecimal("2000.50"), ec = ec)!!
        val bracket = req as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.BUY)
        val entry = bracket.entry as OrderRequest.Limit
        assertThat(entry.limitPrice).isEqualByComparingTo("1996.50") // O - 4
        assertThat(bracket.takeProfit).isEqualByComparingTo("2005.50") // O + 5
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1988.50") // O - 12
    }

    @Test
    fun `short break mirrors the geometry as a SELL`() {
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(direction = -1, anchor = BigDecimal("1999.50"), ec = ec)!!
        val bracket = req as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.SELL)
        val entry = bracket.entry as OrderRequest.Limit
        assertThat(entry.limitPrice).isEqualByComparingTo("2003.50") // O + 4
        assertThat(bracket.takeProfit).isEqualByComparingTo("1994.50") // O - 5
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("2011.50") // O + 12
    }

    @Test
    fun `latch entry stamps timestamp and expiry from the engine clock`() {
        val exprCompiler = ExprCompiler()
        val sizingCompiler = SizingCompiler(exprCompiler)
        val ids = SequentialIdGenerator(prefix = "latch-ts-")
        // The engine clock lives in the EvalContext and is the FixedClock a backtest replays on.
        // The order must take that clock, not wall-clock.
        val compiler = LatchCompiler(exprCompiler, sizingCompiler, ids)
        val compiled = compiler.compile(limitLatch(), strategyId = "test")
        val ec =
            LatchCompilerFixture.ctx(
                symbol = "XAUUSD",
                close = BigDecimal("2000.00"),
                clock = FixedClock(time = 9_999_000L),
            )
        val req = compiled.entryBuilders.single().build(1, BigDecimal("2000.50"), ec) as OrderRequest.Bracket
        assertThat(req.timestamp).isEqualTo(9_999_000L)
        assertThat(req.expiresAt).isEqualTo(9_999_000L + 7_200_000L)
    }

    @Test
    fun `risk sizing uses the static stop distance abs(sl - entryDepth)`() {
        // stopDistance = |12 - 4| = 8 ; risk $250 / (8 * contractSize=1) = 31.25
        val compiled = LatchCompilerFixture.compile(limitLatch())
        val ec = LatchCompilerFixture.ctx(symbol = "XAUUSD", close = BigDecimal("2000.00"))
        val req = compiled.entryBuilders.single().build(1, BigDecimal("2000.50"), ec) as OrderRequest.Bracket
        assertThat(req.quantity).isEqualByComparingTo("31.25")
    }
}
