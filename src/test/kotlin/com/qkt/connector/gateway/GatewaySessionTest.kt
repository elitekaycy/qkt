package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Strategies in their own sessions sharing one gateway account. */
internal class GatewaySessionTest : GatewayHarness() {
    @Test
    fun `each strategy's fills reach its own session only`() {
        val shared = session(setOf("a", "b"))
        val a = Strategy()
        val b = Strategy()
        val brokerA = broker(shared, a, "a", shared = true)
        broker(shared, b, "b", shared = true)

        brokerA.submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill("a-1", "f1", "0.1", "650", FakeGateway.TIME) }
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }

        assertThat(a.of<BrokerEvent.OrderFilled>().single().strategyId).isEqualTo("a")
        assertThat(b.events).isEmpty()
    }

    @Test
    fun `a settlement reaches every strategy, its costs shared once by holding across the sessions`() {
        val shared = session(setOf("a", "b"))
        val a = Strategy().apply { held[symbol] = BigDecimal("0.3") }
        val b = Strategy().apply { held[symbol] = BigDecimal("-0.1") }
        broker(shared, a, "a", shared = true)
        broker(shared, b, "b", shared = true)

        fake.act {
            settle(
                WireSettlement(code, "1000", FakeGateway.TIME, listOf(WireCost("delivery_fee", "2", "USDC"))),
            )
        }

        await { a.events.isNotEmpty() && b.events.isNotEmpty() }
        val shares =
            listOf(a, b).map {
                it
                    .of<ContractSettled>()
                    .single()
                    .costs
                    .single()
                    .amount.amount
            }
        assertThat(shares.map { it.toPlainString() }).containsExactly("1.5", "0.5")
    }

    @Test
    fun `under the kill switch only an order reducing both its strategy and the account goes through`() {
        val shared = session(setOf("a", "b"))
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val b = Strategy().apply { held[symbol] = BigDecimal("-0.1") }
        val brokerA = broker(shared, a, "a", shared = true)
        fake.killed = true

        brokerA.submit(market("a-1", "a", Side.SELL))

        await { a.events.isNotEmpty() }
        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason).startsWith("kill_switch:")
        assertThat(b.events).isEmpty()
    }

    @Test
    fun `a gateway on another account never trades, and a failed first attach leaves the session reusable`() {
        val shared = session()
        fake.login = "8"
        val failed = Strategy()
        assertThatThrownBy { broker(shared, failed, "a") }.hasMessageContaining("account '8'")

        fake.login = "7"
        val a = Strategy()
        val brokerA = broker(shared, a, "a")
        brokerA.submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill("a-1", "f1", "0.1", "650", FakeGateway.TIME) }

        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        assertThat(failed.events).isEmpty()
    }

    @Test
    fun `a gateway that comes back on another account refuses every later order`() {
        val shared = session()
        val a = Strategy()
        val brokerA = broker(shared, a, "a")
        await { fake.streams > 0 }
        fake.login = "8"
        fake.reset()
        await { shared.refused != null }

        brokerA.submit(market("a-1", "a"))

        await { a.events.isNotEmpty() }
        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason).contains("account '8'")
    }

    @Test
    fun `a strategy that stops and starts again on the account keeps trading on the same connection`() {
        val shared = session()
        broker(shared, Strategy(), "a").shutdown()
        val again = Strategy()
        val brokerA = broker(shared, again, "a")

        brokerA.submit(market("a-2", "a"))
        await { again.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill("a-2", "f2", "0.1", "650", FakeGateway.TIME) }

        await { again.of<BrokerEvent.OrderFilled>().isNotEmpty() }
    }
}
