package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The account check of a gateway account shared with other holders (`shared_account: true`). */
internal class GatewaySharedAccountTest : GatewayHarness() {
    private val other = "ETH_USDC-PERPETUAL"

    /** Another holder's order [id] on [venueCode], filled for [quantity] (signed by [side]). */
    private fun foreign(
        id: String,
        quantity: String,
        venueCode: String = code,
        side: String = "buy",
    ) {
        fake.act {
            place(WireSubmit(id, venueCode, side, "market", quantity, null, null, "gtc", false))
            fill(id, "f-$id", quantity, "650", FakeGateway.TIME)
        }
    }

    private fun entered(strategy: Strategy): Boolean {
        await { strategy.events.isNotEmpty() }
        return strategy.events.single() is BrokerEvent.OrderAccepted
    }

    @Test
    fun `another holder's size on the code a strategy trades lets its entries through`() {
        foreign("x-1", "0.003")
        val a = Strategy()
        val brokerA = broker(session(shared = true), a, "a").apply { watchBookedLegs { emptyList() } }

        brokerA.submit(market("a-1", "a"))

        assertThat(entered(a)).isTrue()
    }

    @Test
    fun `another holder's size on a code no strategy here trades blocks nothing`() {
        fake.codes = fake.codes + other
        foreign("x-1", "2", other)
        val a = Strategy().apply { held[symbol] = BigDecimal.ZERO }
        val brokerA = broker(session(shared = true), a, "a").apply { watchBookedLegs { emptyList() } }

        brokerA.submit(market("a-1", "a"))

        assertThat(entered(a)).isTrue()
    }

    @Test
    fun `a move on a traded code that no fill here explains still refuses entries`() {
        foreign("x-1", "0.003")
        val shared = session(shared = true)
        val a = Strategy().apply { held[symbol] = BigDecimal.ZERO }
        broker(shared, a, "a").watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()

        foreign("x-2", "0.5")
        await { shared.account.quantity(code).compareTo(BigDecimal("0.503")) == 0 }
        val b = Strategy()
        val brokerB = broker(shared, b, "b").apply { watchBookedLegs { emptyList() } }
        brokerB.submit(market("b-1", "b"))

        assertThat(entered(b)).isFalse()
        assertThat((b.events.single() as BrokerEvent.OrderRejected).reason)
            .isEqualTo(
                "the account's $code moved without a fill here: strategies hold 0, account holds 0.503, " +
                    "other holders held 0.003",
            )
    }

    @Test
    fun `a strategy's own fills explain the account's move, so a later check agrees`() {
        foreign("x-1", "0.003")
        val shared = session(shared = true)
        val a = Strategy()
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }
        brokerA.submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill(wire("a-1"), "f1", "0.1", "650", FakeGateway.TIME) }
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        a.held[symbol] = BigDecimal("0.1")
        await { shared.account.quantity(code).compareTo(BigDecimal("0.103")) == 0 }

        broker(shared, Strategy(), "b").watchBookedLegs { emptyList() }

        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `a restart beside another holder's size restores the strategy's own book and trades on`() {
        foreign("a-0.5", "0.1")
        foreign("x-1", "0.3", side = "sell")
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val shared = session(shared = true)
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }

        brokerA.submit(market("a-1", "a"))

        assertThat(entered(a)).isTrue()
        assertThat(a.positions.positionFor(symbol)?.quantity).isEqualByComparingTo("0.1")
        assertThat(brokerA.isAccountWide(symbol)).isTrue()
    }

    @Test
    fun `an account qkt owns alone still refuses entries beside size no strategy holds`() {
        foreign("x-1", "0.003")
        val a = Strategy()
        val sole = session()
        val brokerA = broker(sole, a, "a").apply { watchBookedLegs { emptyList() } }

        brokerA.submit(market("a-1", "a", Side.BUY))

        assertThat(entered(a)).isFalse()
        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason)
            .isEqualTo("strategies hold, account holds: $code 0 vs 0.003")
    }
}
