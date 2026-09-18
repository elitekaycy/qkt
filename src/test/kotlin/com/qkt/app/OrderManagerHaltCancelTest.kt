package com.qkt.app

import com.qkt.app.OrderManagerFixtures.bracket
import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.Broker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderModification
import com.qkt.broker.SubmitAck
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerHaltCancelTest {
    @Test
    fun `halt cancellation is strategy scoped and preserves risk reducing exits`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                isRiskReducingForHalt = { it.id == "a-exit" },
            )

        fun limit(
            id: String,
            strategyId: String,
            side: Side,
        ) = OrderRequest.Limit(
            id = id,
            symbol = "EURUSD",
            side = side,
            quantity = Money.of("1"),
            limitPrice = Money.of("1.10"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = strategyId,
        )

        om.submit(limit("a-entry", "A", Side.BUY))
        om.submit(limit("a-exit", "A", Side.SELL))
        om.submit(limit("b-entry", "B", Side.BUY))

        om.cancelEntriesForHalt("A")

        assertThat(om.getOrder("a-entry")?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(om.getOrder("a-exit")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder("b-entry")?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `halt cancellation retries until confirmed and alerts after repeated misses`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val cancellations = mutableListOf<String>()
        val alerts = mutableListOf<String>()
        val broker =
            object : Broker {
                override val name = "unconfirmed-cancel"
                override val capabilities = emptySet<com.qkt.broker.OrderTypeCapability>()

                override fun submit(request: OrderRequest): SubmitAck {
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            request.id,
                            request.id,
                            request.strategyId,
                            clock.now(),
                        ),
                    )
                    return SubmitAck(request.id, request.id, accepted = true)
                }

                override fun cancel(orderId: String) {
                    cancellations += orderId
                }

                override fun modify(
                    orderId: String,
                    changes: OrderModification,
                ) = SubmitAck(orderId, orderId, accepted = false)
            }
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts += message },
            )
        om.submit(
            OrderRequest.Limit(
                id = "entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "A",
            ),
        )

        om.cancelEntriesForHalt("A")
        om.retryHaltCancellations(1_000L)
        om.retryHaltCancellations(3_000L)

        assertThat(cancellations).containsExactly("entry", "entry", "entry")
        assertThat(alerts.single()).contains("CRITICAL", "unconfirmed", "entry")
    }

    @Test
    fun `a global halt keeps a filled bracket's protective exits working`() {
        // Forge #2401, 2023-12-11: a daily-drawdown halt fired mid-bar and the open longs' working
        // stops never filled; the legs were closed three days later after the market recovered.
        // A halt must stop NEW exposure only — the way out of a position is never cancelled.
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val net =
            object : com.qkt.positions.PositionProvider {
                override fun positionFor(symbol: String) =
                    com.qkt.positions.Position(
                        symbol = symbol,
                        quantity = Money.of("1"),
                        avgEntryPrice = Money.of("1.10"),
                    )

                override fun allPositions() = mapOf("EURUSD" to positionFor("EURUSD"))
            }
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                isRiskReducingForHalt = { com.qkt.risk.isRiskReducing(it, net) },
            )
        om.submit(bracket("b1", "e1"))
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "e1",
                brokerOrderId = "e1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )
        assertThat(om.getOrder("b1-sl")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder("b1-tp")?.state).isEqualTo(OrderState.WORKING)
        // A market tick runs the order GC: the filled entry is dead and unreferenced, so it leaves
        // the live map exactly as it does in a replay — the OTO wrapper can no longer prove a
        // filled child and must survive on its live protective children alone.
        bus.publish(com.qkt.events.TickEvent(com.qkt.marketdata.Tick("EURUSD", Money.of("1.10"), 1L)))
        assertThat(om.getOrder("e1")).isNull()

        om.cancelEntriesForHalt(null)

        assertThat(om.getOrder("b1-sl")?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder("b1-tp")?.state).isEqualTo(OrderState.WORKING)
    }
}
