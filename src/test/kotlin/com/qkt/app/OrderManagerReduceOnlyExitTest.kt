package com.qkt.app

import com.qkt.broker.FakeBroker
import com.qkt.broker.OrderTypeCapability
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.MarketPriceTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Reduce-only tripwire (#1069): a protective exit fill that leaves the net position on the
 * fill's own side ADDED exposure — the manager must raise the operator protection alert.
 * A fill that reduced or flattened stays silent. The stale-exit sweep prevents the known
 * path; this detector refuses to let any future path fail silently.
 */
class OrderManagerReduceOnlyExitTest {
    private fun stopExit(
        id: String,
        side: Side,
    ): OrderRequest.Stop =
        OrderRequest.Stop(
            id = id,
            symbol = "X",
            side = side,
            quantity = Money.of("1"),
            stopPrice = Money.of("99"),
            timeInForce = TimeInForce.GTC,
            timestamp = 0L,
            strategyId = "s",
        )

    private fun harness(netQty: BigDecimal): Triple<OrderManager, FakeBroker, MutableList<String>> {
        val clock = FixedClock(1_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.STOP))
        val alerts = mutableListOf<String>()
        val om =
            OrderManager(
                broker,
                bus,
                MarketPriceTracker(),
                clock,
                onProtectionFailure = { _, message -> alerts.add(message) },
                strategyNetQty = { _, _ -> netQty },
            )
        return Triple(om, broker, alerts)
    }

    @Test
    fun `an exit fill that lands the position on its own side raises the protection alert`() {
        // Net LONG 1 after a BUY "-sl" fill: the exit added long exposure (the silver tape).
        val (om, broker, alerts) = harness(netQty = BigDecimal.ONE)
        val exit = stopExit("b1-sl", Side.BUY)
        om.submit(exit)

        broker.emitFill(exit, Money.of("99"))

        assertThat(alerts).singleElement().asString().contains("REDUCE-ONLY VIOLATION").contains("b1-sl")
    }

    @Test
    fun `an exit fill that flattens or reduces stays silent`() {
        // Net flat after a BUY "-sl" fill: a short was legitimately stopped out.
        val (om, broker, alerts) = harness(netQty = BigDecimal.ZERO)
        val exit = stopExit("b2-sl", Side.BUY)
        om.submit(exit)

        broker.emitFill(exit, Money.of("99"))

        assertThat(alerts).isEmpty()
    }

    @Test
    fun `a non-exit fill on its own side is not a violation`() {
        // A plain entry naturally lands the position on its own side; the tripwire only
        // covers protective-exit ids.
        val (om, broker, alerts) = harness(netQty = BigDecimal.ONE)
        val entry = stopExit("entry-1", Side.BUY)
        om.submit(entry)

        broker.emitFill(entry, Money.of("99"))

        assertThat(alerts).isEmpty()
    }
}
