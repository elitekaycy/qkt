package com.qkt.connector.gateway

import com.qkt.common.Side
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
}
