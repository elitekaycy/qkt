package com.qkt.broker.exchange

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExpirySettlementTest {
    private val f = ExchangeFixture(slippageTicks = 0)

    @Test
    fun `a position held into expiry is closed at the delivery price as a venue close`() {
        f.tick(f.sep, "63100.0")
        f.sim.submit(f.market("long", Side.BUY, "0.01"))
        f.sim.submit(f.stop("protect", Side.SELL, "62000.0"))

        f.tick(f.sep, "63000.5", atMs = f.sepExpiry)

        val cancel = f.only<BrokerEvent.OrderCancelled>().single()
        assertThat(cancel.clientOrderId).isEqualTo("protect")
        assertThat(cancel.reason).contains("BINANCE_UM:BTCUSDT_240927 expired")
        val settle = f.only<BrokerEvent.OrderFilled>().last()
        assertThat(settle.side).isEqualTo(Side.SELL)
        assertThat(settle.quantity).isEqualByComparingTo("0.01")
        assertThat(settle.price).isEqualByComparingTo("63000.5")
        assertThat(settle.strategyId).isEqualTo("s")
        assertThat(settle.updatesOrderExecution).isFalse()
        assertThat(settle.exitReason).isEqualTo(ExitReason.EXPIRY)
        assertThat(f.events.indexOf(cancel)).isLessThan(f.events.indexOf(settle))
    }

    @Test
    fun `a short is bought back and each strategy settles its own net position`() {
        f.tick(f.sep, "63100.0")
        f.sim.submit(f.market("a", Side.SELL, "0.02"))
        f.sim.submit(f.market("b", Side.BUY, "0.005").copy(strategyId = "other"))

        f.tick("BINANCE_UM:BTCUSDT_241227", "63900.0", atMs = f.sepExpiry + 60_000L)

        val settles = f.only<BrokerEvent.OrderFilled>().filter { it.exitReason == ExitReason.EXPIRY }
        assertThat(settles.map { Triple(it.strategyId, it.side, it.quantity.toPlainString()) })
            .containsExactlyInAnyOrder(Triple("s", Side.BUY, "0.020"), Triple("other", Side.SELL, "0.005"))
    }

    @Test
    fun `orders on an expired contract are rejected`() {
        f.tick(f.sep, "63000.5", atMs = f.sepExpiry)

        val ack = f.sim.submit(f.market("late", Side.BUY, "0.01"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("expired at 2024-09-27T08:00:00Z")
    }

    @Test
    fun `a flat strategy gets no settlement fill`() {
        f.tick(f.sep, "63100.0")
        f.sim.submit(f.market("in", Side.BUY, "0.01"))
        f.sim.submit(f.market("out", Side.SELL, "0.01"))

        f.tick(f.sep, "63000.5", atMs = f.sepExpiry)

        assertThat(f.only<BrokerEvent.OrderFilled>().map { it.clientOrderId }).containsExactly("in", "out")
    }

    @Test
    fun `a contract without a delivery price settles at its last price`() {
        f.tick(f.dec, "64000.0")
        f.sim.submit(f.market("long", Side.BUY, "0.01", symbol = f.dec))

        f.tick(f.dec, "64123.4", atMs = f.decExpiry)

        val settle = f.only<BrokerEvent.OrderFilled>().last()
        assertThat(settle.exitReason).isEqualTo(ExitReason.EXPIRY)
        assertThat(settle.price).isEqualByComparingTo("64123.4")
    }

    @Test
    fun `ticks on an expired contract no longer trigger its orders`() {
        f.tick(f.sep, "63100.0")
        f.sim.submit(f.stop("protect", Side.SELL, "62000.0"))

        f.tick(f.sep, "61000.0", atMs = f.sepExpiry)
        f.tick(f.sep, "60000.0", atMs = f.sepExpiry + 1)

        assertThat(f.only<BrokerEvent.OrderFilled>()).isEmpty()
    }
}
