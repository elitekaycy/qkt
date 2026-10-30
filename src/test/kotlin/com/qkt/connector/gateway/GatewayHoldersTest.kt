package com.qkt.connector.gateway

import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The account-level holdings check as strategies deploy one after another on one gateway account. */
internal class GatewayHoldersTest : GatewayHarness() {
    @Test
    fun `the first strategy up may only reduce until the others holding the account are ready too`() {
        val shared = session(emptySet())
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val b = Strategy().apply { held[symbol] = BigDecimal("-0.1") }
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }
        assertThat(shared.riskRefused).startsWith("strategies hold, account holds:")

        broker(shared, b, "b").watchBookedLegs { emptyList() }

        assertThat(shared.riskRefused).isNull()
        brokerA.submit(market("a-1", "a", Side.BUY))
        await { a.events.isNotEmpty() }
        assertThat(a.events.single()).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
    }

    @Test
    fun `a fill the venue had not yet reported when checked does not block later entries`() {
        val shared = session(setOf("a"))
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }
        assertThat(shared.riskRefused).startsWith("strategies hold, account holds:")

        fake.act {
            place(WireSubmit("x-1", code, "buy", "market", "0.1", null, null, "gtc", false))
            fill("x-1", "f1", "0.1", "650", FakeGateway.TIME)
        }
        await { shared.account.quantity(code).compareTo(BigDecimal("0.1")) == 0 }
        brokerA.submit(market("a-1", "a", Side.BUY))
        await { a.events.isNotEmpty() }

        assertThat(a.events.single()).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `a strategy deployed but not yet ready holds the check back`() {
        val shared = session(setOf("a", "b"))
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }

        broker(shared, a, "a").watchBookedLegs { emptyList() }

        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `an account one strategy trades is account-wide too, so its book is restored and checked, never adopted`() {
        val brokerA = broker(session(), Strategy(), "a")

        assertThat(brokerA.isAccountWide(symbol)).isTrue()
        assertThat(brokerA.isAccountWide("EXNESS:XAUUSD")).isFalse()
    }

    @Test
    fun `a stream lane not yet ready holds the check back, so a restart over its contract never refuses risk`() {
        fake.act { held("x-1", "sell", "0.1") }
        val shared = session(setOf("a"))
        val own = broker(shared, Strategy(), "a")
        val lane = broker(shared, Strategy().apply { held[symbol] = BigDecimal("-0.1") }, "a")

        own.watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()

        lane.watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `once a strategy's lane is ready too, a real difference is still refused`() {
        fake.act { held("x-1", "sell", "0.1") }
        val shared = session(setOf("a"))
        val own = broker(shared, Strategy(), "a")
        val lane = broker(shared, Strategy().apply { held[symbol] = BigDecimal("-0.2") }, "a")

        own.watchBookedLegs { emptyList() }
        lane.watchBookedLegs { emptyList() }

        assertThat(shared.riskRefused).startsWith("strategies hold, account holds:")
    }

    @Test
    fun `a lane of a broker serving every strategy holds the check back as well`() {
        fake.act { held("x-1", "sell", "0.1") }
        val shared = session(emptySet())
        val every = { s: Strategy -> GatewayBroker(shared, s.bus, SystemClock(), s.positions, null) }
        val own = every(Strategy())
        val lane = every(Strategy().apply { held[symbol] = BigDecimal("-0.1") })

        own.watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()

        lane.watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `a restart judges nothing until the daemon has deployed the strategy holding the account`() {
        fake.act { held("x-1", "buy", "0.1") }
        var deploying = true
        val shared = session(emptySet(), deploysUnderway = { deploying })

        broker(shared, Strategy(), "first").watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()

        broker(shared, Strategy().apply { held[symbol] = BigDecimal("0.1") }, "second").watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()

        deploying = false
        shared.deploysFinished()
        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `a deploy that failed lets the check judge once the daemon is through, and its retry clears the refusal`() {
        fake.act { held("x-1", "buy", "0.1") }
        var deploying = true
        val shared = session(emptySet(), deploysUnderway = { deploying })
        broker(shared, Strategy(), "first").watchBookedLegs { emptyList() }

        deploying = false
        shared.deploysFinished()
        assertThat(shared.riskRefused).startsWith("strategies hold, account holds:")

        broker(shared, Strategy().apply { held[symbol] = BigDecimal("0.1") }, "failed").watchBookedLegs { emptyList() }
        assertThat(shared.riskRefused).isNull()
    }

    @Test
    fun `a broker that never gets ready stops holding the check back after the limit, so entries are refused`() {
        fake.act { held("x-1", "buy", "0.1") }
        val time = Later()
        val shared = session(setOf("a", "hung"), time = time)
        val a = Strategy()
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }
        broker(shared, Strategy(), "hung")
        assertThat(shared.riskRefused).isNull()

        time.pass(GatewayHolders.HOLD_LIMIT_MS)
        brokerA.submit(market("a-1", "a", Side.BUY))
        await { a.events.isNotEmpty() }

        assertThat(a.events.single()).isInstanceOf(BrokerEvent.OrderRejected::class.java)
        assertThat(shared.riskRefused).startsWith("strategies hold, account holds:")
    }

    @Test
    fun `the daemon stuck deploying stops holding the check back after the limit`() {
        fake.act { held("x-1", "buy", "0.1") }
        val time = Later()
        val shared = session(emptySet(), time = time, deploysUnderway = { true })
        val a = Strategy()
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }
        assertThat(shared.riskRefused).isNull()

        time.pass(GatewayHolders.HOLD_LIMIT_MS)
        brokerA.submit(market("a-1", "a", Side.BUY))
        await { a.events.isNotEmpty() }

        assertThat(a.events.single()).isInstanceOf(BrokerEvent.OrderRejected::class.java)
    }

    @Test
    fun `a broker attached after a long quiet gets the whole limit to become ready`() {
        fake.act { held("x-1", "buy", "0.1") }
        val time = Later()
        val shared = session(setOf("a", "late"), time = time)
        val a = Strategy()
        val brokerA = broker(shared, a, "a").apply { watchBookedLegs { emptyList() } }

        time.pass(GatewayHolders.HOLD_LIMIT_MS)
        broker(shared, Strategy().apply { held[symbol] = BigDecimal("0.1") }, "late")
        brokerA.submit(market("a-1", "a", Side.BUY))
        await { a.events.isNotEmpty() }

        assertThat(a.events.single()).isInstanceOf(BrokerEvent.OrderAccepted::class.java)
        assertThat(shared.riskRefused).isNull()
    }

    /** Wall time that can be moved ahead. */
    private class Later : Clock {
        private var ahead = 0L

        override fun now() = System.currentTimeMillis() + ahead

        fun pass(ms: Long) {
            ahead += ms
        }
    }

    private fun FakeVenue.held(
        id: String,
        side: String,
        quantity: String,
    ) {
        place(WireSubmit(id, code, side, "market", quantity, null, null, "gtc", false))
        fill(id, "f-$id", quantity, "650", FakeGateway.TIME)
    }
}
