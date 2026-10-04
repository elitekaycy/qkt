package com.qkt.broker.exchange

import com.qkt.common.Side
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Within 24 hours of expiry (the default guard) a contract takes exits only. */
class ExpiryGuardTest {
    private val f = ExchangeFixture(slippageTicks = 0)
    private val inside = f.sepExpiry - 3_600_000L

    @Test
    fun `an opening order inside the guard window is refused naming the expiry`() {
        f.tick(f.sep, "63000.0", atMs = inside)

        val ack = f.sim.submit(f.market("late", Side.BUY, "0.01"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("24h").contains("2024-09-27T08:00:00Z")
    }

    @Test
    fun `adding is refused but reducing and closing pass`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("in", Side.BUY, "0.02"))
        f.tick(f.sep, "63000.0", atMs = inside)

        val add = f.sim.submit(f.market("add", Side.BUY, "0.01"))
        val trim = f.sim.submit(f.market("trim", Side.SELL, "0.01"))
        val flip = f.sim.submit(f.market("flip", Side.SELL, "0.02"))
        val close = f.sim.submit(f.market("close", Side.SELL, "0.01"))

        assertThat(listOf(add, trim, flip, close).map { it.accepted }).containsExactly(false, true, false, true)
    }

    @Test
    fun `before the window every order passes`() {
        f.tick(f.sep, "63000.0", atMs = f.sepExpiry - 25 * 3_600_000L)

        assertThat(f.sim.submit(f.market("early", Side.BUY, "0.01")).accepted).isTrue()
    }

    @Test
    fun `a guard of zero hours turns the guard off`() {
        val open = ExchangeFixture(slippageTicks = 0, expiryGuardHours = 0)
        open.tick(open.sep, "63000.0", atMs = inside)

        assertThat(open.sim.submit(open.market("late", Side.BUY, "0.01")).accepted).isTrue()
    }

    @Test
    fun `working orders count, so stacked exits cannot flip the position inside the window`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("in", Side.BUY, "0.02"))
        f.tick(f.sep, "63000.0", atMs = inside)

        val first = f.sim.submit(f.limit("exit1", Side.SELL, "64000.0", qty = "0.02"))
        val second = f.sim.submit(f.limit("exit2", Side.SELL, "64000.0", qty = "0.02"))

        assertThat(first.accepted).isTrue()
        assertThat(second.accepted).isFalse()
    }

    @Test
    fun `a bracket's take-profit and stop both stand inside the window, as they never fill on one tick`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("in", Side.BUY, "0.01"))
        f.sim.submit(f.limit("tp", Side.SELL, "65000.0"))
        f.sim.submit(f.stop("sl", Side.SELL, "61000.0"))

        f.tick(f.sep, "63000.0", atMs = inside)

        assertThat(f.only<com.qkt.events.BrokerEvent.OrderCancelled>()).isEmpty()
        assertThat(f.sim.submit(f.market("close", Side.SELL, "0.01")).accepted).isTrue()
    }

    @Test
    fun `once the position is closed inside the window its leftover exits are cancelled`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("in", Side.BUY, "0.01"))
        f.sim.submit(f.limit("tp", Side.SELL, "65000.0"))
        f.sim.submit(f.stop("sl", Side.SELL, "61000.0"))
        f.tick(f.sep, "63000.0", atMs = inside)

        f.tick(f.sep, "65000.0", atMs = inside + 1_000)
        f.tick(f.sep, "64000.0", atMs = inside + 2_000)

        assertThat(
            f.only<com.qkt.events.BrokerEvent.OrderFilled>().map { it.clientOrderId },
        ).containsExactly("in", "tp")
        assertThat(f.only<com.qkt.events.BrokerEvent.OrderCancelled>().map { it.clientOrderId }).containsExactly("sl")
    }

    @Test
    fun `an opening order resting from before the window is cancelled when the window starts`() {
        f.tick(f.sep, "63000.0", atMs = f.sepExpiry - 30 * 3_600_000L)
        f.sim.submit(f.limit("rest", Side.BUY, "62000.0"))

        f.tick(f.sep, "62000.0", atMs = inside)

        assertThat(f.only<com.qkt.events.BrokerEvent.OrderFilled>()).isEmpty()
        assertThat(f.only<com.qkt.events.BrokerEvent.OrderCancelled>().single().reason).contains("24h")
    }
}
