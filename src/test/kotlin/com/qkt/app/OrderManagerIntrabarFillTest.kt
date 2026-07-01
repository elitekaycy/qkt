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
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceTracker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `intrabarFill` is the three-way resolution behind tick-resolved fills: given a bar's price range
 * and the live orders on a symbol, how few real ticks can the replay feed and stay byte-identical?
 * SYNTHETIC (no fill possible), EXTREMES (static orders — feed new-extreme ticks), ALL_TICKS (a
 * trailing/composite order or a time-based exit forces a full real-tick replay).
 */
class OrderManagerIntrabarFillTest {
    private fun newOm(): OrderManager {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val broker = FakeBroker(bus, clock, setOf(OrderTypeCapability.MARKET))
        return OrderManager(broker, bus, MarketPriceTracker(), clock)
    }

    private fun buyStop(
        symbol: String,
        price: String,
        expiresAt: Long? = null,
    ) = OrderRequest.Stop(
        id = "s",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("1"),
        stopPrice = Money.of(price),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        expiresAt = expiresAt,
    )

    @Test
    fun `no live order is synthetic`() {
        assertThat(newOm().intrabarFill("X", Money.of("0"), Money.of("9999")))
            .isEqualTo(IntrabarFill.SYNTHETIC)
    }

    @Test
    fun `a static stop in range resolves to extremes`() {
        val om = newOm()
        om.submit(buyStop("X", "100"))
        assertThat(om.intrabarFill("X", Money.of("98"), Money.of("101")))
            .isEqualTo(IntrabarFill.EXTREMES)
    }

    @Test
    fun `a static stop out of range is synthetic`() {
        val om = newOm()
        om.submit(buyStop("X", "100"))
        assertThat(om.intrabarFill("X", Money.of("96"), Money.of("99")))
            .isEqualTo(IntrabarFill.SYNTHETIC)
    }

    @Test
    fun `a trailing stop forces a full replay`() {
        val om = newOm()
        om.submit(
            OrderRequest.TrailingStop(
                id = "t",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                trailAmount = Money.of("0.5"),
                trailMode = TrailMode.ABSOLUTE,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(om.intrabarFill("X", Money.of("98"), Money.of("101")))
            .isEqualTo(IntrabarFill.ALL_TICKS)
    }

    @Test
    fun `a GTD order forces a full replay even if static and in range`() {
        val om = newOm()
        om.submit(buyStop("X", "100", expiresAt = 5_000L)) // time-based -> can expire mid-bar
        assertThat(om.intrabarFill("X", Money.of("98"), Money.of("101")))
            .isEqualTo(IntrabarFill.ALL_TICKS)
    }

    @Test
    fun `another symbol's static order is synthetic`() {
        val om = newOm()
        om.submit(buyStop("A", "100"))
        assertThat(om.intrabarFill("B", Money.of("98"), Money.of("101")))
            .isEqualTo(IntrabarFill.SYNTHETIC)
    }
}
