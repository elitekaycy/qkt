package com.qkt.app

import com.qkt.app.OrderManagerFixtures.newBus
import com.qkt.broker.LogBroker
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class NotifierSubscriptionsTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    private class Recording(
        private val fail: Boolean = false,
    ) : Notifier {
        val events = mutableListOf<NotificationEvent>()

        override fun notify(event: NotificationEvent) {
            if (fail) error("telegram down")
            events += event
        }

        override fun close() {}
    }

    private class Harness(
        kinds: Set<NotifyEventKind>,
        strategies: List<Pair<String, Strategy>>,
        failing: Boolean = false,
    ) {
        val bus = newBus()
        val clock = FixedClock(0L)
        val notifier = Recording(failing)
        val failures = mutableListOf<Pair<String?, String>>()
        val orderManager = OrderManager(LogBroker(bus, clock), bus, MarketPriceTracker(), clock)

        init {
            NotifierSubscriptions(notifier, kinds, strategies) { id, handler, _ -> failures += id to handler }
                .wire(bus, orderManager)
        }
    }

    private fun protection(strategyId: String) =
        BrokerEvent.PositionProtectionChanged(
            broker = "mt5",
            symbol = "XAUUSD",
            ticket = "77",
            oldStopLoss = BigDecimal("1900"),
            newStopLoss = BigDecimal("1890"),
            oldTakeProfit = BigDecimal("2000"),
            newTakeProfit = BigDecimal("2010"),
            strategyId = strategyId,
        )

    @Test
    fun `halts resumes and reconciles become operator alerts owned by the first strategy`() {
        val kinds = setOf(NotifyEventKind.HALTED, NotifyEventKind.RESUMED, NotifyEventKind.POSITION_RECONCILED)
        val h = Harness(kinds, listOf("s1" to idle, "s2" to idle))

        h.bus.publish(RiskEvent.Halted(reason = "daily loss", strategyId = "s1"))
        h.bus.publish(RiskEvent.Resumed(strategyId = "s1"))
        h.bus.publish(
            BrokerEvent.PositionReconciled("XAUUSD", null, BigDecimal.ONE, null, BigDecimal.TEN, "venue", "drift"),
        )

        assertThat(h.notifier.events).containsExactly(
            NotificationEvent.Halted("s1", "daily loss", 0L),
            NotificationEvent.Resumed("s1", 0L),
            NotificationEvent.PositionReconciled("s1", "XAUUSD", null, BigDecimal.ONE, "drift", 0L),
        )
    }

    @Test
    fun `gateway equity and protection faults become strategy error alerts`() {
        val h = Harness(setOf(NotifyEventKind.STRATEGY_ERROR), listOf("owner" to idle))

        h.bus.publish(BrokerEvent.GatewayUnreachable("mt5", 4))
        h.bus.publish(BrokerEvent.AccountEquityStale("mt5", 3, staleForMs = 9000L))
        h.bus.publish(BrokerEvent.AccountEquityStale("mt5", 5, staleForMs = null))
        h.bus.publish(protection(strategyId = ""))
        h.bus.publish(protection(strategyId = "named"))

        val errors = h.notifier.events.map { it as NotificationEvent.StrategyError }
        assertThat(errors.map { it.strategyId }).containsExactly("owner", "owner", "owner", "owner", "named")
        assertThat(errors[0].message).contains("gateway 'mt5' unreachable for 4 consecutive polls")
        assertThat(errors[1].message).contains("last good sample is 9000ms old")
        assertThat(errors[2].message).contains("(no successful sample)")
        assertThat(errors[3].message).contains("ticket=77").contains("SL 1900->1890").contains("TP 2000->2010")
    }

    @Test
    fun `a rejected order is alerted with the details the order manager recovers`() {
        val h = Harness(setOf(NotifyEventKind.ORDER_REJECTED), emptyList())
        h.orderManager.submit(
            OrderRequest.Limit(
                id = "o1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("2"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
                strategyId = "s1",
            ),
        )

        h.bus.publish(BrokerEvent.OrderRejected("o1", null, "no money", strategyId = "s1"))
        h.bus.publish(BrokerEvent.OrderRejected("ghost", null, "unknown", strategyId = "s1"))

        assertThat(h.notifier.events).containsExactly(
            NotificationEvent.OrderRejected("s1", "EURUSD", Side.SELL, Money.of("2"), "no money", 0L),
        )
    }

    @Test
    fun `a notifier fault is recorded per handler and never escapes the bus`() {
        val all = NotifyEventKind.entries.toSet()
        val h = Harness(all, emptyList(), failing = true)
        h.orderManager.submit(
            OrderRequest.Limit("o1", "EURUSD", Side.BUY, Money.of("1"), Money.of("1.1"), TimeInForce.GTC, 0L, "s9"),
        )

        h.bus.publish(RiskEvent.Halted(reason = "x", strategyId = "s9"))
        h.bus.publish(RiskEvent.Resumed(strategyId = "s9"))
        h.bus.publish(BrokerEvent.PositionReconciled("X", null, BigDecimal.ONE, null, BigDecimal.ONE, "v", "r"))
        h.bus.publish(BrokerEvent.GatewayUnreachable("mt5", 1))
        h.bus.publish(BrokerEvent.AccountEquityStale("mt5", 1, null))
        h.bus.publish(protection(""))
        h.bus.publish(BrokerEvent.OrderRejected("o1", null, "r", strategyId = "s9"))

        assertThat(h.failures).containsExactly(
            "s9" to "Halted",
            "s9" to "Resumed",
            "" to "PositionReconciled",
            "" to "GatewayUnreachable",
            "" to "AccountEquityStale",
            "" to "PositionProtectionChanged",
            "s9" to "OrderRejected",
        )
    }

    @Test
    fun `kinds the session did not opt into stay silent`() {
        val h = Harness(emptySet(), listOf("s1" to idle))

        h.bus.publish(RiskEvent.Halted(reason = "x", strategyId = "s1"))
        h.bus.publish(BrokerEvent.GatewayUnreachable("mt5", 1))

        assertThat(h.notifier.events).isEmpty()
    }
}
