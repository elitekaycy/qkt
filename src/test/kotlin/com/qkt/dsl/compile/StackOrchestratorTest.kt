package com.qkt.dsl.compile

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackOrchestratorTest {
    private fun tier(
        threshold: String = "0.005",
        within: Long = 30 * 60 * 1000L,
        qty: String = "0.05",
    ) = CompiledStackTier(
        mfeThreshold = BigDecimal(threshold),
        withinMs = within,
        stackQuantity = BigDecimal(qty),
        slDistance = BigDecimal("0.005"),
        tpDistance = BigDecimal("0.020"),
    )

    @Test
    fun `no engines registered means ticks are no-ops`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onTick("EURUSD", BigDecimal("1.1050"))
        assertThat(orch.activeCount()).isEqualTo(0)
        assertThat(captured).isEmpty()
    }

    @Test
    fun `onPrimaryFilled with empty tiers does not register an engine`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onPrimaryFilled(
            parentLegId = "p1",
            parentSymbol = "EURUSD",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("1.1000"),
            tiers = emptyList(),
        )
        assertThat(orch.activeCount()).isEqualTo(0)
        assertThat(orch.hasEngineFor("p1")).isFalse
    }

    @Test
    fun `onPrimaryFilled registers an engine that fires on tick`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onPrimaryFilled(
            parentLegId = "p1",
            parentSymbol = "EURUSD",
            parentSide = Side.BUY,
            parentEntryPrice = BigDecimal("1.1000"),
            tiers = listOf(tier()),
        )
        assertThat(orch.activeCount()).isEqualTo(1)
        orch.onTick("EURUSD", BigDecimal("1.1060")) // MFE = 0.006, fires
        assertThat(captured).hasSize(1)
        val req = (captured[0] as Signal.Submit).request as OrderRequest.Bracket
        assertThat(req.symbol).isEqualTo("EURUSD")
        assertThat(req.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `onTick only dispatches to engines for the matching symbol`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onPrimaryFilled("p-eur", "EURUSD", Side.BUY, BigDecimal("1.1000"), listOf(tier()))
        orch.onPrimaryFilled("p-gbp", "GBPUSD", Side.BUY, BigDecimal("1.2500"), listOf(tier()))
        // Tick on EURUSD only — only p-eur should fire
        orch.onTick("EURUSD", BigDecimal("1.1060"))
        assertThat(captured).hasSize(1)
        val req = (captured[0] as Signal.Submit).request as OrderRequest.Bracket
        assertThat(req.symbol).isEqualTo("EURUSD")
    }

    @Test
    fun `onPrimaryClosed removes the engine and stops further ticks affecting it`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onPrimaryFilled("p1", "EURUSD", Side.BUY, BigDecimal("1.1000"), listOf(tier()))
        assertThat(orch.activeCount()).isEqualTo(1)
        orch.onPrimaryClosed("p1")
        assertThat(orch.activeCount()).isEqualTo(0)
        orch.onTick("EURUSD", BigDecimal("1.1100"))
        assertThat(captured).isEmpty()
    }

    @Test
    fun `onPrimaryClosed for an unknown id is a no-op`() {
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { }
        orch.onPrimaryClosed("does-not-exist") // must not throw
        assertThat(orch.activeCount()).isEqualTo(0)
    }

    @Test
    fun `registering the same parentLegId twice fails loudly`() {
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { }
        orch.onPrimaryFilled("p1", "EURUSD", Side.BUY, BigDecimal("1.1000"), listOf(tier()))
        assertThatThrownBy {
            orch.onPrimaryFilled("p1", "EURUSD", Side.BUY, BigDecimal("1.1000"), listOf(tier()))
        }.isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already registered")
    }

    @Test
    fun `multiple engines on same symbol all fire on a qualifying tick`() {
        val captured = mutableListOf<Signal>()
        val orch = StackOrchestrator(FixedClock(time = 1_000L)) { captured.add(it) }
        orch.onPrimaryFilled("p1", "EURUSD", Side.BUY, BigDecimal("1.1000"), listOf(tier()))
        orch.onPrimaryFilled("p2", "EURUSD", Side.BUY, BigDecimal("1.1010"), listOf(tier()))
        orch.onTick("EURUSD", BigDecimal("1.1080"))
        // p1: MFE = 0.008 (≥ 0.005), fires. p2: MFE = 0.007 (≥ 0.005), fires.
        assertThat(captured).hasSize(2)
    }
}
