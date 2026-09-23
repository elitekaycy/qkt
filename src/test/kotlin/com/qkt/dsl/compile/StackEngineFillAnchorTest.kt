package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.NumLit
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A firing tier's bracket starts at the quote its market leg fills at, while excursion stays on the mark. */
class StackEngineFillAnchorTest {
    private fun tier(
        mfeThreshold: String,
        sl: String = "0.005",
        tp: String = "0.020",
    ) = ResolvedStackTier(
        mfeThreshold = BigDecimal(mfeThreshold),
        withinMs = 30 * 60 * 1000L,
        stackQuantity = BigDecimal("0.05"),
        slDistance = BigDecimal(sl),
        tpDistance = BigDecimal(tp),
        maeRecoverDistance = null,
    )

    private fun newEngine(
        tiers: List<ResolvedStackTier>,
        parentSide: Side = Side.BUY,
    ): Pair<StackEngine, MutableList<Signal>> {
        val captured = mutableListOf<Signal>()
        val engine =
            StackEngine(
                parentLegId = "parent-1",
                parentSymbol = "BACKTEST:EURUSD",
                parentSide = parentSide,
                parentEntryPrice = BigDecimal("1.1000"),
                tiers = tiers,
                clock = FixedClock(time = 1_000L),
                emit = { captured.add(it) },
            )
        return engine to captured
    }

    @Test
    fun `BUY tier anchors its bracket at the ask a market BUY fills at, not the mid`() {
        val (engine, captured) = newEngine(listOf(tier("0.005")))

        engine.onTick(BigDecimal("1.1055"), bid = BigDecimal("1.1050"), ask = BigDecimal("1.1060"))

        val bracket = (captured.single() as Signal.Submit).request as OrderRequest.Bracket
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1.1010")
        assertThat(bracket.takeProfit).isEqualByComparingTo("1.1260")
    }

    @Test
    fun `SELL tier anchors its bracket at the bid a market SELL fills at, not the mid`() {
        val (engine, captured) = newEngine(listOf(tier("0.005")), parentSide = Side.SELL)

        engine.onTick(BigDecimal("1.0945"), bid = BigDecimal("1.0940"), ask = BigDecimal("1.0950"))

        val bracket = (captured.single() as Signal.Submit).request as OrderRequest.Bracket
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1.0990")
        assertThat(bracket.takeProfit).isEqualByComparingTo("1.0740")
    }

    @Test
    fun `MFE stays measured on the mid even when the ask alone would cross the threshold`() {
        val (engine, captured) = newEngine(listOf(tier("0.005")))

        engine.onTick(BigDecimal("1.1049"), bid = BigDecimal("1.1038"), ask = BigDecimal("1.1060"))

        assertThat(captured).isEmpty()
        assertThat(engine.mfe()).isEqualByComparingTo("0.0049")
    }

    @Test
    fun `stack bracket carries its distances so the leg re-anchors on its own fill`() {
        val (engine, captured) = newEngine(listOf(tier("0.005", sl = "0.005", tp = "0.020")))

        engine.onTick(BigDecimal("1.1055"), bid = BigDecimal("1.1050"), ask = BigDecimal("1.1060"))

        val bracket = (captured.single() as Signal.Submit).request as OrderRequest.Bracket
        assertThat(bracket.stopLossAst).isEqualTo(ChildBy(NumLit(BigDecimal("0.005"))))
        assertThat(bracket.takeProfitAst).isEqualTo(ChildBy(NumLit(BigDecimal("0.020"))))
    }
}
