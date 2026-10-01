package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The gateway failing, lagging or losing events: nothing is lost, doubled or left hanging. */
internal class GatewayFailureTest : GatewayHarness() {
    @Test
    fun `a resync that fails is alerted and owed, then made on a later event`() {
        val a = Strategy()
        broker(session(), a, "a").submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() && fake.streams > 0 }
        fake.failing["/v1/deals"] = 1
        fake.quiet = true
        fake.act { fill("a-1", "f1", "0.1", "650", FakeGateway.TIME) }
        fake.quiet = false

        fake.reset()
        await { a.of<BrokerEvent.GatewayUnreachable>().isNotEmpty() }
        Thread.sleep(60)
        fake.act { settle(WireSettlement("BTC_USDC-27DEC26-1-C", "0", FakeGateway.TIME)) }

        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        assertThat(a.of<BrokerEvent.OrderFilled>().single().price).isEqualByComparingTo("650")
    }

    @Test
    fun `a fill for a strategy that stepped away waits for it to attach again`() {
        val shared = session(setOf("a", "b"))
        val b = Strategy()
        broker(shared, Strategy(), "a", shared = true)
        val brokerB = broker(shared, b, "b", shared = true)
        brokerB.submit(market("b-1", "b"))
        await { b.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        brokerB.shutdown()

        fake.act { fill("b-1", "f1", "0.1", "650", FakeGateway.TIME) }
        Thread.sleep(100)
        val back = Strategy()
        broker(shared, back, "b", shared = true)

        assertThat(back.of<BrokerEvent.OrderFilled>().single().clientOrderId).isEqualTo("b-1")
    }

    @Test
    fun `a submit the gateway never answers is resolved by id, and the id can never place an order later`() {
        val a = Strategy()
        val brokerA = broker(session(), a, "a")
        fake.unreachable = 1_000

        brokerA.submit(market("a-1", "a"))

        await { a.events.isNotEmpty() }
        assertThat((a.events.single() as BrokerEvent.OrderRejected).reason).contains("never received it")
        fake.unreachable = 0
        assertThat(fake.submits).isEmpty()
    }

    @Test
    fun `an order the venue ended while its events were lost ends in the engine at the next resync`() {
        val a = Strategy()
        broker(session(), a, "a").submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() && fake.streams > 0 }
        fake.quiet = true
        fake.act { cancel("a-1") }
        fake.quiet = false

        fake.reset()

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
    }

    @Test
    fun `a contract listed after the start is tradeable once the listing refreshes`() {
        val later = "BTC_USDC-26DEC26-95000-C"
        val a = Strategy()
        val brokerA = broker(session(), a, "a")
        val order = market("a-1", "a").copy(symbol = "DERIBIT:BTC_USDC_26DEC26_95000_C")
        fake.codes = fake.codes + later

        assertThat(brokerA.submit(order).accepted).isFalse()
        await { brokerA.supports(order.symbol) }
        brokerA.submit(order.copy(id = "a-2"))

        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
    }

    @Test
    fun `strategies whose holdings disagree with the account may only reduce`() {
        val a = Strategy().apply { held[symbol] = BigDecimal("0.2") }
        val brokerA = broker(session(), a, "a")
        brokerA.watchBookedLegs { emptyList() }

        brokerA.submit(market("a-1", "a"))

        await { a.events.isNotEmpty() }
        assertThat(
            (a.events.single() as BrokerEvent.OrderRejected).reason,
        ).startsWith("strategies hold, account holds:")
    }
}
