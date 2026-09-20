package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.persistence.NoopStatePersistor

object OrderManagerStopRatchetFixtures {
    fun stepped(id: String = "step-sl") =
        OrderRequest.SteppedStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            initialDistance = Money.of("50"),
            steps =
                listOf(
                    StopLossSpec.Step(Money.of("30"), Money.ZERO),
                    StopLossSpec.Step(Money.of("70"), Money.of("40")),
                ),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    fun timeTighten(id: String = "time-sl") =
        OrderRequest.TimeTighteningStop(
            id = id,
            symbol = "X",
            side = Side.SELL,
            quantity = Money.of("1"),
            entryPrice = Money.of("100"),
            initialDistance = Money.of("60"),
            tightenBy = Money.of("10"),
            intervalMs = 900_000L,
            floorDistance = Money.of("20"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "alpha",
        )

    data class Fixture(
        val clock: FixedClock,
        val bus: EventBus,
        val broker: FakeBroker,
        val manager: OrderManager,
    )

    fun fixture(
        capabilities: Set<OrderTypeCapability> = setOf(OrderTypeCapability.MARKET),
        persistor: NoopStatePersistor = NoopStatePersistor(),
        closeTicket: String? = null,
    ): Fixture {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, capabilities)
        val manager =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                persistor = persistor,
                closeTicketFor = { _, _ -> closeTicket },
            )
        return Fixture(clock, bus, broker, manager)
    }

    fun Fixture.tick(
        price: String,
        timestamp: Long,
    ) {
        clock.advanceTo(timestamp)
        bus.publish(TickEvent(Tick("X", Money.of(price), timestamp)))
    }
}
