package com.qkt.app

import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.FakeBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.observe.insights.TicketAttribution
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class SessionReconcileReportTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val pnl = StrategyPnL(positions, MarketPriceTracker())
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private fun report(failure: Exception): ReconcileReport {
        val broker =
            object : com.qkt.broker.Broker by FakeBroker(bus, clock, emptySet()) {
                override fun positionTickets(): List<BrokerPositionTicket> = throw failure

                override fun accountEquity(): BigDecimal = BigDecimal("1000")
            }
        pnl.setStartingBalance("gold", BigDecimal("900"))
        // Run on the engine thread itself, so the engine snapshot reads inline.
        val snapshot = EngineSnapshot(Thread.currentThread(), EngineMailbox())
        return SessionReconcileReport(
            listOf("gold" to idle),
            listOf("EXNESS:XAUUSD"),
            broker,
            TicketAttribution(),
            positions,
            pnl,
            snapshot,
        ).reconcile()
    }

    @Test
    fun `a failed venue position read is reported with its message, not thrown`() {
        val result = report(IllegalStateException("gateway timeout"))

        assertThat(result.brokerReadFailed).isTrue()
        assertThat(result.brokerReadError).isEqualTo("gateway timeout")
        assertThat(result.clean).isFalse()
        assertThat(result.deltas).isEmpty()
        assertThat(result.engineEquity).isEqualByComparingTo("900")
        assertThat(result.brokerEquity).isEqualByComparingTo("1000")
    }

    @Test
    fun `a failed read with no message is reported by its exception type`() {
        val result = report(IllegalStateException())

        assertThat(result.brokerReadError).isEqualTo("IllegalStateException")
    }
}
