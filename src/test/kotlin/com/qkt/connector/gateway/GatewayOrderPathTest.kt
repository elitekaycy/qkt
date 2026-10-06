package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** An account's orders when the gateway reports them out of order, errs, or cannot be reached: no fill lost, no order placed twice. */
internal class GatewayOrderPathTest : GatewayHarness() {
    private fun Strategy.trail() = events.filterIsInstance<BrokerEvent.OrderEvent>().map { it::class.simpleName }

    @Test
    fun `an order that ends at the venue before its fill is heard ends in the engine only after the fill`() {
        val a = Strategy()
        broker(session(), a, "a").submit(market("a-1", "a", quantity = "0.2"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }

        fake.act { cancelAfterFilling(wire("a-1"), "f1", "0.1", "650") }

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        assertThat(a.trail()).containsExactly("OrderAccepted", "OrderPartiallyFilled", "OrderCancelled")
        assertThat(a.of<BrokerEvent.OrderPartiallyFilled>().single().quantity).isEqualByComparingTo("0.1")
    }

    @Test
    fun `an order that ended with a fill the stream never carried has its deals fetched, then ends`() {
        val a = Strategy()
        broker(session(), a, "a").submit(market("a-1", "a", quantity = "0.2"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }

        fake.act { cancelAfterFilling(wire("a-1"), "f1", "0.1", "650", pushFill = false) }

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        assertThat(a.trail()).containsExactly("OrderAccepted", "OrderPartiallyFilled", "OrderCancelled")
    }

    @Test
    fun `a server error on a submit is sent again, not taken as a rejection`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        fake.failing["/v1/orders"] = 2

        broker.submit(market("a-1", "a"))

        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        assertThat(a.of<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(fake.submits.map { it.clientOrderId }).containsExactly(wire("a-1"))
    }

    @Test
    fun `a cancel sent while its submit is still unanswered cancels the order once it is placed`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        fake.unreachable = 3

        broker.submit(market("a-1", "a"))
        broker.cancel("a-1")

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        assertThat(
            fake.venue.orders
                .getValue(wire("a-1"))
                .status,
        ).isEqualTo("cancelled")
    }

    @Test
    fun `a submit resolved by id after its deadline books its fills before the order's end`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        fake.quiet = true
        fake.act {
            place(WireSubmit(wire("a-1"), code, "buy", "market", "0.2", null, null, "gtc", false))
            fill(wire("a-1"), "f1", "0.1", "650", FakeGateway.TIME)
            cancel(wire("a-1"))
        }
        fake.quiet = false
        fake.failing["/v1/orders"] = 1_000

        broker.submit(market("a-1", "a", Side.BUY, "0.2"))

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        // First seen already ended: its fill, then its end, and nothing dropped.
        assertThat(a.trail()).containsExactly("OrderPartiallyFilled", "OrderCancelled")
    }

    @Test
    fun `a market order answered already cancelled with a part filled books that part, ends, and is sent once`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        // The gateway already holds it ended, so the POST answers with it, as a 201 for an IOC remainder would.
        fake.quiet = true
        fake.act {
            place(WireSubmit(wire("a-1"), code, "buy", "market", "0.5", null, null, "gtc", false))
            cancelAfterFilling(wire("a-1"), "f1", "0.3", "650")
        }
        fake.quiet = false

        broker.submit(market("a-1", "a", Side.BUY, "0.5"))

        await { a.of<BrokerEvent.OrderCancelled>().isNotEmpty() }
        assertThat(a.trail()).containsExactly("OrderPartiallyFilled", "OrderCancelled")
        assertThat(a.of<BrokerEvent.OrderPartiallyFilled>().single().cumulativeFilled).isEqualByComparingTo("0.3")
        Thread.sleep(200)
        // Nothing places the remainder: the gateway's only order is the ended one.
        assertThat(fake.venue.orders.keys).containsExactly(wire("a-1"))
        assertThat(fake.submits).isEmpty()
        assertThat(a.trail()).hasSize(2)
    }

    @Test
    fun `a refused submit is rejected once, though the gateway also reports the rejection`() {
        val shared = session()
        val a = Strategy()
        val broker = broker(shared, a, "a")
        fake.killed = true

        broker.submit(market("a-1", "a"))

        await { a.of<BrokerEvent.OrderRejected>().isNotEmpty() }
        assertThat(a.of<BrokerEvent.OrderRejected>()).hasSize(1)
    }
}
