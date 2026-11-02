package com.qkt.connector.gateway

import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** The wire form of a market order per listed kind, through the broker and a fake gateway. */
internal class GatewayCollaredOrderTest : GatewayHarness() {
    private val strategy = Strategy()

    private fun brokerListed(kind: String): GatewayBroker {
        fake.instruments[code] = WireInstrument(code, kind, "USDC", "1", "2.5", "0.001", "0.001")
        val prices = MarketPriceTracker().also { it.update(Tick(symbol, BigDecimal("77400"), 1L)) }
        val session = session()
        val broker = GatewayBroker(session, strategy.bus, SystemClock(), strategy.positions, "a", prices)
        await {
            val listed = session.symbols.instrument(symbol)
            listed?.kind == kind
        }
        return broker
    }

    private fun sell() = market("o", "a", Side.SELL)

    private val placed: List<WireOrder>
        get() {
            val orders = fake.venue.orders
            return orders.values.toList()
        }

    @Test
    fun `a dated future's market order reaches the gateway as a limit ioc at the collar`() {
        brokerListed("future").submit(sell())
        await { fake.venue.orders.isNotEmpty() }

        val order = placed.single()
        assertThat(order.type).isEqualTo("limit")
        assertThat(order.timeInForce).isEqualTo("ioc")
        assertThat(order.limitPrice?.let(::BigDecimal)).isEqualByComparingTo("58050")
    }

    @Test
    fun `an exit on a dated future stays a market order`() {
        brokerListed("future").submit(sell().copy(closesTicket = "t-1"))
        await { fake.venue.orders.isNotEmpty() }

        assertThat(placed.single().type).isEqualTo("market")
    }

    @Test
    fun `an option's market order stays a market order`() {
        brokerListed("option").submit(sell())
        await { fake.venue.orders.isNotEmpty() }

        assertThat(placed.single().type).isEqualTo("market")
    }

    @Test
    fun `a venue's cancel is sent again, and the engine hears nothing of it`() {
        brokerListed("future").submit(sell())
        await { fake.venue.orders.size == 1 }
        fake.act { cancel(orders.keys.single()) }

        await { fake.venue.orders.size == 2 }
        assertThat(strategy.of<BrokerEvent.OrderCancelled>()).isEmpty()
        assertThat(strategy.of<BrokerEvent.OrderRejected>()).isEmpty()
        assertThat(placed.map { GatewayClientIds.engineId(it.clientOrderId) }).containsOnly("o")
    }

    @Test
    fun `an order refused by the venue's kill switch is refused, not sent again`() {
        val broker = brokerListed("future")
        fake.killed = true
        broker.submit(sell())

        await { strategy.of<BrokerEvent.OrderRejected>().isNotEmpty() }
        val rejected = strategy.of<BrokerEvent.OrderRejected>().single()
        assertThat(rejected.reason).contains("kill_switch")
    }
}
