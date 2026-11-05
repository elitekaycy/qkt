package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.RiskEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.RiskState
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #1645: an OutOfMemoryError in the engine loop halted eight strategies PERSISTENT, and the halts outlived the
 * restart that cured the heap. A JVM resource fault now halts for this process only, still loudly.
 */
class EngineFaultsTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val prices = MarketPriceTracker()
    private val riskState =
        RiskState(
            PnLCalculator(StrategyPositionTracker().account, prices),
            StrategyPnL(StrategyPositionTracker(), prices),
            clock,
            bus,
        )
    private val halts = mutableListOf<RiskEvent.Halted>()
    private val pages = mutableListOf<String>()
    private val notifier =
        object : Notifier {
            override fun notify(event: NotificationEvent) {
                if (event is NotificationEvent.StrategyError) pages += event.message
            }

            override fun close() {}
        }
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }
    private val strategies = listOf("soak_o12_xau" to strategy)
    private val faults =
        EngineFaults(
            strategies,
            riskState,
            SessionNotifier(notifier, setOf(NotifyEventKind.STRATEGY_ERROR), null, strategies, clock),
        )

    init {
        bus.subscribe<RiskEvent.Halted> { halts += it }
    }

    @Test
    fun `an out-of-memory fault halts for this process only and still pages`() {
        faults.onEngineFault("schedule heartbeat", OutOfMemoryError("GC overhead limit exceeded"))

        assertThat(riskState.halted).isTrue()
        assertThat(halts.single().scope).isEqualTo("TRANSIENT")
        assertThat(halts.single().reason).contains("GC overhead limit exceeded")
        assertThat(pages.single()).contains("GC overhead limit exceeded")
    }

    @Test
    fun `a strategy's own fault still halts until an operator resumes`() {
        faults.onEngineFault("tick EXNESS:XAUUSD@1", ArithmeticException("/ by zero"))

        assertThat(halts.single().scope).isEqualTo("PERSISTENT")
    }
}
