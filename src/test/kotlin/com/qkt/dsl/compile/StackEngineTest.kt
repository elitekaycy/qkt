package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StackEngineTest {
    private fun tier(
        mfeThreshold: String,
        withinMs: Long = 30 * 60 * 1000L,
        stackQuantity: String = "0.05",
        sl: String = "0.005",
        tp: String = "0.020",
    ) = ResolvedStackTier(
        mfeThreshold = BigDecimal(mfeThreshold),
        withinMs = withinMs,
        stackQuantity = BigDecimal(stackQuantity),
        slDistance = BigDecimal(sl),
        tpDistance = BigDecimal(tp),
    )

    private fun newEngine(
        tiers: List<ResolvedStackTier>,
        clock: FixedClock = FixedClock(time = 1_000L),
        captured: MutableList<Signal> = mutableListOf(),
        parentSide: Side = Side.BUY,
        parentEntryPrice: String = "1.1000",
    ): Pair<StackEngine, MutableList<Signal>> {
        val engine =
            StackEngine(
                parentLegId = "parent-1",
                parentSymbol = "BACKTEST:EURUSD",
                parentSide = parentSide,
                parentEntryPrice = BigDecimal(parentEntryPrice),
                tiers = tiers,
                clock = clock,
                emit = { captured.add(it) },
            )
        return engine to captured
    }

    @Test
    fun `no ticks no signals`() {
        val (engine, captured) = newEngine(listOf(tier("0.005")))
        assertThat(captured).isEmpty()
        assertThat(engine.firedCount()).isEqualTo(0)
    }

    @Test
    fun `BUY tier fires when MFE crosses threshold within window`() {
        val clock = FixedClock(time = 1_000L)
        val (engine, captured) = newEngine(listOf(tier("0.005")), clock = clock)
        engine.onTick(BigDecimal("1.1010")) // MFE = 0.001 — below threshold
        assertThat(captured).isEmpty()
        engine.onTick(BigDecimal("1.1055")) // MFE = 0.0055 — crosses threshold
        assertThat(captured).hasSize(1)
        val sig = captured[0] as Signal.Submit
        val bracket = sig.request as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.BUY)
        assertThat(bracket.quantity).isEqualByComparingTo("0.05") // absolute, from tier
        // SL = currentPrice - slDistance = 1.1055 - 0.005 = 1.1005
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1.1005")
        // TP = currentPrice + tpDistance = 1.1055 + 0.020 = 1.1255
        assertThat(bracket.takeProfit).isEqualByComparingTo("1.1255")
    }

    @Test
    fun `tier fires at most once even on subsequent favorable ticks`() {
        val (engine, captured) = newEngine(listOf(tier("0.005")))
        engine.onTick(BigDecimal("1.1060"))
        engine.onTick(BigDecimal("1.1100"))
        engine.onTick(BigDecimal("1.1200"))
        assertThat(captured).hasSize(1)
        assertThat(engine.firedCount()).isEqualTo(1)
    }

    @Test
    fun `tier abandons when WITHIN elapses without MFE reaching threshold`() {
        val clock = FixedClock(time = 1_000L)
        val (engine, captured) = newEngine(listOf(tier("0.050", withinMs = 1_000L)), clock = clock)
        engine.onTick(BigDecimal("1.1010")) // MFE = 0.001, well below
        assertThat(captured).isEmpty()
        clock.time = 3_000L // elapsed = 2_000ms > withinMs of 1_000
        engine.onTick(BigDecimal("1.1500")) // MFE would be 0.05 but window already expired
        assertThat(captured).isEmpty()
        assertThat(engine.abandonedCount()).isEqualTo(1)
        assertThat(engine.firedCount()).isEqualTo(0)
    }

    @Test
    fun `multiple tiers fire in MFE order as thresholds cross`() {
        val (engine, captured) =
            newEngine(
                listOf(
                    tier("0.005"),
                    tier("0.010"),
                    tier("0.020"),
                ),
            )
        engine.onTick(BigDecimal("1.1050")) // MFE = 0.005 — only tier 0
        assertThat(captured).hasSize(1)
        engine.onTick(BigDecimal("1.1100")) // MFE = 0.010 — tier 1
        assertThat(captured).hasSize(2)
        engine.onTick(BigDecimal("1.1300")) // MFE = 0.030 — tier 2
        assertThat(captured).hasSize(3)
    }

    @Test
    fun `single huge tick can fire multiple tiers at once`() {
        val (engine, captured) =
            newEngine(
                listOf(
                    tier("0.005"),
                    tier("0.010"),
                    tier("0.020"),
                ),
            )
        engine.onTick(BigDecimal("1.1500")) // MFE = 0.05 — crosses all three
        assertThat(captured).hasSize(3)
        assertThat(engine.firedCount()).isEqualTo(3)
        assertThat(engine.isTerminal()).isTrue
    }

    @Test
    fun `SELL parent direction inverts excursion and bracket sides`() {
        val (engine, captured) =
            newEngine(
                listOf(tier("0.005", stackQuantity = "0.1")),
                parentSide = Side.SELL,
                parentEntryPrice = "1.1000",
            )
        engine.onTick(BigDecimal("1.0930")) // For SELL: MFE = entry - price = 0.0070 — crosses 0.005
        assertThat(captured).hasSize(1)
        val sig = captured[0] as Signal.Submit
        val bracket = sig.request as OrderRequest.Bracket
        assertThat(bracket.side).isEqualTo(Side.SELL)
        // SL for SELL = currentPrice + slDistance = 1.0930 + 0.005 = 1.0980
        assertThat((bracket.stopLoss as StopLossSpec.Fixed).price).isEqualByComparingTo("1.0980")
        // TP for SELL = currentPrice - tpDistance = 1.0930 - 0.020 = 1.0730
        assertThat(bracket.takeProfit).isEqualByComparingTo("1.0730")
    }

    @Test
    fun `mfe accessor exposes current high-water mark`() {
        val (engine, _) = newEngine(listOf(tier("100"))) // threshold never reached
        engine.onTick(BigDecimal("1.1050")) // MFE = 0.005
        engine.onTick(BigDecimal("1.1100")) // MFE = 0.010
        engine.onTick(BigDecimal("1.0900")) // MFE stays 0.010
        assertThat(engine.mfe()).isEqualByComparingTo("0.010")
    }

    @Test
    fun `isTerminal becomes true after all tiers fired or abandoned`() {
        val clock = FixedClock(time = 1_000L)
        val (engine, _) =
            newEngine(
                listOf(
                    tier("0.005", withinMs = 10_000L), // will fire
                    tier("100", withinMs = 1_000L), // will abandon
                ),
                clock = clock,
            )
        engine.onTick(BigDecimal("1.1060")) // tier 0 fires
        clock.time = 3_000L
        engine.onTick(BigDecimal("1.1200")) // tier 1 abandons
        assertThat(engine.isTerminal()).isTrue
        assertThat(engine.firedCount()).isEqualTo(1)
        assertThat(engine.abandonedCount()).isEqualTo(1)
    }
}
