package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.FundingCharged
import java.math.BigDecimal
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A gateway account's perpetual funding, reaching each session to book its own part. */
internal class GatewayFundingTest : GatewayHarness() {
    private fun Strategy.funding() =
        CopyOnWriteArrayList<FundingCharged>().also { heard ->
            bus.subscribe<FundingCharged> {
                heard +=
                    it
            }
        }

    private fun record(
        id: String,
        position: String?,
        time: Long = FakeGateway.TIME,
    ) = WireFunding(id, code, "1.2", "USDC", position, time)

    @Test
    fun `a funding record reaches every session, charged on the position the venue charged`() {
        fake.capabilities = listOf("funding")
        val shared = session(setOf("a", "b"))
        val a = Strategy()
        val b = Strategy()
        val heardA = a.funding()
        val heardB = b.funding()
        broker(shared, a, "a")
        broker(shared, b, "b")

        fake.act { fund(record("tx-1", "0.2")) }

        await { heardA.isNotEmpty() && heardB.isNotEmpty() }
        val charged = heardA.single()
        assertThat(listOf(charged.fundingId, charged.symbol, charged.currency)).containsExactly("tx-1", symbol, "USDC")
        assertThat(charged.amount).isEqualByComparingTo("1.2")
        assertThat(charged.basis).isEqualByComparingTo("0.2")
        assertThat(heardB.single().fundingId).isEqualTo("tx-1")
    }

    @Test
    fun `a record without a position is charged on the account's last known position`() {
        fake.capabilities = listOf("funding")
        val shared = session()
        val a = Strategy()
        val heard = a.funding()
        broker(shared, a, "a").submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill(wire("a-1"), "f1", "0.1", "650", FakeGateway.TIME) }
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }

        fake.act { fund(record("tx-1", null)) }

        await { heard.isNotEmpty() }
        assertThat(heard.single().basis).isEqualByComparingTo("0.1")
    }

    @Test
    fun `without the funding capability an order that could add to a perpetual is refused, a reduction goes`() {
        fake.instruments[code] = WireInstrument(code, "perpetual", "USDC", "1", "0.5", "0.1", "0.1")
        val shared = session()
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val broker = broker(shared, a, "a")
        fake.act {
            place(WireSubmit("held-1", code, "buy", "market", "0.1", null, null, "gtc", false))
            fill("held-1", "f0", "0.1", "650", FakeGateway.TIME)
        }
        await { shared.account.quantity(code).signum() > 0 }

        broker.submit(market("a-1", "a"))
        broker.submit(market("a-2", "a", Side.SELL))

        await { a.of<BrokerEvent.OrderRejected>().isNotEmpty() && a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        assertThat(a.of<BrokerEvent.OrderRejected>().single().reason).contains("does not report funding")
        assertThat(fake.submits.map { it.clientOrderId to it.reduceOnly }).containsExactly(wire("a-2") to true)
    }

    @Test
    fun `a session that becomes ready is handed the funding of the last week, to book what it missed`() {
        fake.capabilities = listOf("funding")
        fake.quiet = true
        fake.act { fund(record("tx-old", "0.1", FakeGateway.TIME - 8 * 86_400_000L)) }
        fake.act { fund(record("tx-missed", "0.1", System.currentTimeMillis() - 3_600_000L)) }
        fake.quiet = false
        val a = Strategy().apply { held[symbol] = BigDecimal("0.1") }
        val heard = a.funding()
        val broker = broker(session(), a, "a")

        broker.watchBookedLegs { emptyList() }

        await { heard.isNotEmpty() }
        assertThat(heard.map { it.fundingId }).containsExactly("tx-missed")
    }
}
