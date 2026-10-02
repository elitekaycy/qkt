package com.qkt.broker.exchange

import com.qkt.accounting.CostKind
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A root's perpetual trades on the exchange like its dated contracts, with no expiry to settle or guard. */
class PerpetualExchangeTest {
    private val f = ExchangeFixture(slippageTicks = 0, takerFeeRate = "0.0005")

    @Test
    fun `a perpetual fill carries the root's taker fee on its notional`() {
        f.tick(f.perp, "86321.2")

        f.sim.submit(f.market("b", Side.BUY, "0.001", symbol = f.perp))

        val fill = f.only<BrokerEvent.OrderFilled>().single()
        assertThat(fill.price).isEqualByComparingTo("86321.2")
        val fee = fill.typedVenueCosts.single()
        assertThat(fee.kind).isEqualTo(CostKind.EXCHANGE_FEE)
        assertThat(fee.amount.amount).isEqualByComparingTo("0.0431606")
    }

    @Test
    fun `a perpetual is never settled or guarded, long after every dated contract expired`() {
        f.tick(f.perp, "63000.0")
        f.sim.submit(f.market("open", Side.BUY, "0.01", symbol = f.perp))

        f.tick(f.perp, "70000.0", atMs = f.decExpiry + 86_400_000L)
        f.sim.submit(f.market("add", Side.BUY, "0.01", symbol = f.perp))

        assertThat(f.only<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(f.only<BrokerEvent.OrderFilled>().map { it.clientOrderId }).containsExactly("open", "add")
        assertThat(f.settlements.entries).isEmpty()
    }
}
