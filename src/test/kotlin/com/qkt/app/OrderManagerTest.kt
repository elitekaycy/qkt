package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.broker.LogBroker
import com.qkt.broker.OrderModification
import com.qkt.broker.PaperBroker
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.dsl.ast.ChildBy
import com.qkt.dsl.ast.ChildRr
import com.qkt.dsl.ast.NumLit
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class OrderManagerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `a late broker event cannot resurrect a terminal order`() {
        // A FILLED order is a sink: a stale OrderAccepted replayed after the fill must
        // not flip it back to a live state (which would re-arm triggers downstream).
        val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
        val clock = FixedClock(time = 0L)
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val broker = PaperBroker(bus, clock, tracker)
        val om = OrderManager(broker, bus, tracker, clock)

        om.submit(
            OrderRequest.Market(
                id = "m-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("m-1")?.state).isEqualTo(OrderState.FILLED)

        // Late/duplicate accept event for the same order.
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                timestamp = 5L,
            ),
        )
        assertThat(om.getOrder("m-1")?.state).isEqualTo(OrderState.FILLED)

        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                reason = "late cancel acknowledgement",
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "m-1",
                brokerOrderId = "b-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.20"),
                quantity = Money.of("1"),
            ),
        )
        val terminal = om.getOrder("m-1")!!
        assertThat(terminal.state).isEqualTo(OrderState.FILLED)
        assertThat(terminal.cumulativeFilledQuantity).isEqualByComparingTo("1")
        assertThat(terminal.avgFillPrice).isEqualByComparingTo("1.10")
    }

    @Test
    fun `cancelled order cannot flip to filled on a late event`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        om.submit(
            OrderRequest.Limit(
                id = "c-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.cancel("c-1")

        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c-1",
                brokerOrderId = "c-1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )

        val terminal = om.getOrder("c-1")!!
        assertThat(terminal.state).isEqualTo(OrderState.CANCELLED)
        assertThat(terminal.cumulativeFilledQuantity).isEqualByComparingTo("0")
    }

    @Test
    fun `submit Market goes to broker and tracks state through accept`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val tracker = MarketPriceTracker()
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, tracker, clock)

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            )
        val ack = om.submit(req)

        assertThat(ack.accepted).isTrue()
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.WORKING)
        assertThat(managed.brokerOrderId).isEqualTo("c1")
    }

    private fun bracket(
        id: String,
        entryId: String,
        symbol: String = "EURUSD",
        entry: String = "1.10",
        stop: String = "1.09",
    ) = OrderRequest.Bracket(
        id = id,
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        entry =
            OrderRequest.Market(
                id = entryId,
                symbol = symbol,
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        takeProfit = Money.of(entry).add(Money.of("20")),
        stopLoss = StopLossSpec.Fixed(Money.of(stop)),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    private fun registry(meta: InstrumentMeta): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == meta.qktSymbol) meta else null
        }

    @Test
    fun `risk is not recorded when tracking is off, so the live map does not leak`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = false)

        om.submit(bracket("b1", "e1"))

        assertThat(om.riskUsdFor("e1")).isNull()
        assertThat(om.riskUsdFor("b1")).isNull()
    }

    @Test
    fun `risk is recorded for the backtest report when tracking is on`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("EURUSD", Money.of("1.10")) }
        val om = OrderManager(broker, bus, tracker, clock, trackRisk = true)

        om.submit(bracket("b1", "e1"))

        // risk = |entry 1.10 - stop 1.09| * qty 1 = 0.01
        assertThat(om.riskUsdFor("e1")).isEqualByComparingTo("0.01")
    }

    @Test
    fun `risk report scales price distance by instrument contract size`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("BACKTEST:XAUUSD", Money.of("2010")) }
        val om =
            OrderManager(
                broker,
                bus,
                tracker,
                clock,
                instruments =
                    registry(
                        InstrumentMeta(
                            qktSymbol = "BACKTEST:XAUUSD",
                            contractSize = BigDecimal("100"),
                            volumeStep = BigDecimal("0.01"),
                            volumeMin = BigDecimal("0.01"),
                            volumeMax = BigDecimal("200"),
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        ),
                    ),
                trackRisk = true,
            )

        om.submit(bracket("b1", "e1", symbol = "BACKTEST:XAUUSD", entry = "2010", stop = "2000"))

        // risk = |entry 2010 - stop 2000| * qty 1 * contractSize 100 = 1000.
        assertThat(om.riskUsdFor("e1")).isEqualByComparingTo("1000")
        val protection = om.protectionFor("e1")
        assertThat(protection?.stopLoss).isEqualByComparingTo("2000")
        assertThat(protection?.takeProfit).isEqualByComparingTo("2030")
    }

    @Test
    fun `entry risk report reanchors relative protection to the exact fill`() {
        val bus = newBus()
        val clock = FixedClock(0L)
        val broker = LogBroker(bus, clock)
        val tracker = MarketPriceTracker().apply { update("BACKTEST:XAUUSD", Money.of("2010")) }
        val om =
            OrderManager(
                broker,
                bus,
                tracker,
                clock,
                instruments =
                    registry(
                        InstrumentMeta(
                            qktSymbol = "BACKTEST:XAUUSD",
                            contractSize = BigDecimal("100"),
                            volumeStep = BigDecimal("0.01"),
                            volumeMin = BigDecimal("0.01"),
                            volumeMax = BigDecimal("200"),
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        ),
                    ),
                trackRisk = true,
            )
        val request =
            bracket("b1", "e1", symbol = "BACKTEST:XAUUSD", entry = "2010", stop = "2000").copy(
                quantity = BigDecimal("0.05"),
                entry =
                    OrderRequest.Market(
                        id = "e1",
                        symbol = "BACKTEST:XAUUSD",
                        side = Side.BUY,
                        quantity = BigDecimal("0.05"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 0L,
                    ),
                stopLossAst = ChildBy(NumLit(BigDecimal("10"))),
                takeProfitAst = ChildRr(NumLit(BigDecimal("2"))),
            )
        om.submit(request)

        val report =
            om.entryRiskForFill(
                clientOrderId = "e1",
                quantity = BigDecimal("0.05"),
                fillPrice = BigDecimal("2010.31"),
                symbol = "BACKTEST:XAUUSD",
            )

        assertThat(report?.riskUsd).isEqualByComparingTo("50")
        assertThat(report?.protection?.stopLoss).isEqualByComparingTo("2000.31")
        assertThat(report?.protection?.takeProfit).isEqualByComparingTo("2030.31")
        assertThat(om.entryRiskForFill("e1", BigDecimal("0.05"), BigDecimal("2010.31"), "BACKTEST:XAUUSD"))
            .isNull()
    }

    @Test
    fun `submit Limit goes to broker`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c2")?.state).isEqualTo(OrderState.WORKING)
    }

    @Test
    fun `OrderFilled event transitions state to FILLED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("1"))
        assertThat(managed.avgFillPrice).isEqualByComparingTo(Money.of("1.10"))
    }

    @Test
    fun `OrderRejected event transitions state to REJECTED`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                reason = "no price",
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.REJECTED)
    }

    @Test
    fun `OrderPartiallyFilled accumulates cumulative fill quantity`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("3"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
                cumulativeFilled = Money.of("1"),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("2"),
                cumulativeFilled = Money.of("3"),
            ),
        )
        val managed = om.getOrder("c1")!!
        assertThat(managed.state).isEqualTo(OrderState.PARTIALLY_FILLED)
        assertThat(managed.cumulativeFilledQuantity).isEqualByComparingTo(Money.of("3"))
    }

    @Test
    fun `activeOrders excludes terminal states`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        om.submit(
            OrderRequest.Market(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = "c1",
                brokerOrderId = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                price = Money.of("1.10"),
                quantity = Money.of("1"),
            ),
        )

        assertThat(om.activeOrders().map { it.id }).containsExactly("c2")
    }

    @Test
    fun `cancel routes to broker for working order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.WORKING)

        om.cancel("c1")
        assertThat(om.getOrder("c1")?.state).isEqualTo(OrderState.CANCELLED)
    }

    @Test
    fun `cancelPendingForSymbol cancels venue-resting working orders`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)
        val eurusd =
            OrderRequest.Limit(
                id = "eurusd-resting",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        val xauusd =
            OrderRequest.Limit(
                id = "xauusd-resting",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        om.submit(eurusd)
        om.submit(xauusd)
        assertThat(om.getOrder(eurusd.id)?.state).isEqualTo(OrderState.WORKING)
        assertThat(om.getOrder(xauusd.id)?.state).isEqualTo(OrderState.WORKING)

        om.cancelPendingForSymbol("EURUSD")

        assertThat(om.getOrder(eurusd.id)?.state).isEqualTo(OrderState.CANCELLED)
        assertThat(om.getOrder(xauusd.id)?.state).isEqualTo(OrderState.WORKING)
    }

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
    fun `cancel of unknown order is a no-op`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val broker = LogBroker(bus, clock)
        val om = OrderManager(broker, bus, MarketPriceTracker(), clock)

        om.cancel("does-not-exist")
        assertThat(om.getOrder("does-not-exist")).isNull()
    }

    @Test
    fun `orderDetailsFor returns symbol side and quantity for a submitted order`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("0.5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("EURUSD")
        assertThat(details.side).isEqualTo(Side.SELL)
        assertThat(details.quantity).isEqualByComparingTo(Money.of("0.5"))
    }

    @Test
    fun `orderDetailsFor still resolves after the order is rejected`() {
        val bus = newBus()
        val clock = FixedClock(time = 100L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        om.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("2"),
                timeInForce = TimeInForce.GTC,
                timestamp = 100L,
            ),
        )
        bus.publish(BrokerEvent.OrderRejected(clientOrderId = "c1", brokerOrderId = null, reason = "test"))

        val details = om.orderDetailsFor("c1")
        assertThat(details).isNotNull
        assertThat(details!!.symbol).isEqualTo("XAUUSD")
        assertThat(details.side).isEqualTo(Side.BUY)
    }

    @Test
    fun `orderDetailsFor returns null for an unknown order`() {
        val bus = newBus()
        val clock = FixedClock(time = 0L)
        val om = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        assertThat(om.orderDetailsFor("never-submitted")).isNull()
    }
}
