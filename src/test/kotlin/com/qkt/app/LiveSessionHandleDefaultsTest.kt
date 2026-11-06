package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.persistence.PersistenceHealth
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.HaltScope
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSessionHandleDefaultsTest {
    /** The least a handle must implement; everything else falls back to the interface defaults. */
    private class MinimalHandle : LiveSessionHandle {
        val calls = mutableListOf<String>()
        override val running = true
        override val droppedTicks = 0L

        override fun stop() {
            calls += "stop"
        }

        override fun awaitTermination(timeout: Duration) = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() {
            calls += "flatten"
        }
    }

    @Test
    fun `a minimal handle reports nothing halted, stale, held or measured`() {
        val handle = MinimalHandle()

        assertThat(handle.haltReason()).isNull()
        assertThat(handle.haltScope()).isNull()
        assertThat(handle.haltedAtMs()).isNull()
        assertThat(handle.isHalted()).isFalse()
        assertThat(handle.strategyHalts()).isEmpty()
        assertThat(handle.unexpectedFeedEnd()).isNull()
        assertThat(handle.limitReading()).isNull()
        assertThat(handle.staleSymbols()).isEmpty()
        assertThat(handle.clockSkewedSymbols()).isEmpty()
        assertThat(handle.persistenceHealth()).isEqualTo(PersistenceHealth.DISABLED)
        assertThat(handle.reconcile()).isNull()
        assertThat(handle.positionsFor("alpha")).isEmpty()
        assertThat(handle.dailySummaryRows()).isEmpty()
        assertThat(handle.latencySnapshot().enabled).isFalse()
        assertThat(handle.streamBrokers()).isEmpty()
        assertThat(handle.pnlSnapshot("alpha")).isEqualTo(SessionPnl.ZERO)
        assertThat(handle.realizedPnl("alpha")).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(handle.bookLegs("alpha")).isEmpty()
    }

    @Test
    fun `stop and flatten requests fall back to the plain operations and say they are unverified`() {
        val handle = MinimalHandle()

        handle.requestStop()
        handle.halt("operator", HaltScope.DAILY)
        handle.resume()
        val forStop = handle.flattenForStop()
        val verified = handle.flattenAndVerify(Duration.ofSeconds(1))

        assertThat(handle.calls).containsExactly("stop", "flatten", "flatten")
        assertThat(forStop.verifiedFlat).isFalse()
        assertThat(forStop.detail).contains("cannot verify venue positions")
        assertThat(verified).isEqualTo(FlattenResult(false, detail = forStop.detail))
    }

    @Test
    fun `risk halt reads report when a global halt tripped`() {
        val clock = FixedClock(1_789_950_000_000L)
        val prices = MarketPriceTracker()
        val positions = StrategyPositionTracker()
        val riskState =
            RiskState(
                PnLCalculator(positions.account, prices),
                StrategyPnL(positions, prices),
                clock,
                EventBus(clock, MonotonicSequenceGenerator()),
            )
        val reads = RiskHaltReads(riskState, listOf("alpha"))
        assertThat(reads.haltedAtMs()).isNull()

        riskState.halt("operator")

        assertThat(reads.haltedAtMs()).isEqualTo(1_789_950_000_000L)
        assertThat(reads.haltScope()).isEqualTo(HaltScope.PERSISTENT)
        assertThat(reads.haltReason()).isEqualTo("operator")
    }
}
