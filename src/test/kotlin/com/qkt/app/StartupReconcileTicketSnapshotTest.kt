package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.execution.OrderRequest
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import com.qkt.observe.insights.TicketAttribution
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionTracker
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.entry
import org.junit.jupiter.api.Test

class StartupReconcileTicketSnapshotTest {
    private val strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `a venue whose position and ticket reads disagree reconciles without ticket scoping`() {
        val broker =
            object : Broker {
                override val name = "disagreeing"
                override val capabilities = emptySet<OrderTypeCapability>()
                override val supportsPositionTickets = true

                override fun submit(request: OrderRequest): SubmitAck = SubmitAck(request.id, null, accepted = true)

                override fun cancel(orderId: String) {}

                override fun getOpenPositions() =
                    mapOf(
                        "XAUUSD" to listOf(Position("XAUUSD", BigDecimal.ONE, BigDecimal("2000"))),
                    )
            }
        val reconcile =
            StartupReconcile(
                listOf("alpha" to strategy),
                listOf("XAUUSD"),
                NoopStatePersistor(),
                FixedClock(0L),
                ignoreMismatches = true,
                reconcileReadBackoffMs = 0L,
                ticketAttribution = TicketAttribution(),
                instruments = NoopInstrumentRegistry,
            )

        val adopted = reconcile.run(StrategyPositionTracker(), broker) { _, _ -> }

        assertThat(adopted).containsExactly(entry("alpha", 1))
    }
}
