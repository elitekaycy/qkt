package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.strategy.Signal
import java.math.BigDecimal

/** A strategy `st` bound to a [StructureCoordinator] over a [StructureBook], with leg orders and event helpers. */
internal abstract class StructureCoordinatorHarness {
    protected val clock = FixedClock(5L)
    protected val bus = EventBus(clock, MonotonicSequenceGenerator())
    protected val emitted = mutableListOf<Signal>()
    protected val cancelled = mutableListOf<String>()
    protected val ids = com.qkt.common.SequentialIdGenerator(prefix = "t-")
    protected val published = mutableListOf<com.qkt.events.StructureEvent>()
    protected val book = StructureBook("st", StructureFixtures.registry, MarketPriceTracker()) { published += it }
    protected val shortPut = StructureFixtures.market("s", StructureFixtures.P81, Side.SELL)
    protected val longPut = StructureFixtures.market("l", StructureFixtures.P78, Side.BUY)
    protected val wing = StructureFixtures.market("w", StructureFixtures.P75, Side.BUY)
    protected val farPut = StructureFixtures.market("f", StructureFixtures.P80_30OCT, Side.BUY)

    init {
        StructureCoordinator(bus, clock) { cancelled += it }.bind("st", book) { signal ->
            emitted += signal
            bus.publish(SignalEvent(signal, strategyId = "st"))
        }
    }

    protected fun open(vararg legs: OrderRequest) =
        bus.publish(SignalEvent(Signal.SubmitGroup("ps-1", "ps", legs.toList()), strategyId = "st"))

    protected fun filled(
        leg: OrderRequest,
        strategyId: String = "st",
    ) = bus.publish(
        BrokerEvent.OrderFilled(
            leg.id,
            leg.id,
            leg.symbol,
            leg.side,
            BigDecimal.TEN,
            leg.quantity,
            strategyId = strategyId,
        ),
    )

    protected fun cancelled(id: String) = bus.publish(BrokerEvent.OrderCancelled(id, id, "no bid", strategyId = "st"))

    protected fun unwinds() = emitted.filterIsInstance<Signal.SubmitGroup>()
}
