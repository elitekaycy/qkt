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
}
