package com.qkt.connector.gateway

import com.qkt.events.BrokerEvent
import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A venue deal is matched to qkt's booking of it by the venue's `fill_id`, never by its time: the paper
 * gateway stamps a fill in `/v1/deals` 26 ms before the time its `fill` event carried (live, 2026-10-21:
 * `paper-9` at …476 in the deal list, …502 on the stream), and every fill was left out.
 */
internal class GatewayDealMatchTest : GatewayHarness() {
    @Test
    fun `a deal stamped apart from its stream fill is matched by its fill id, slice by slice`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        broker.submit(market("a-1", "a", quantity = "0.2"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }

        fake.act {
            fill(wire("a-1"), "paper-9", "0.1", "650", FakeGateway.TIME + 502)
            fill(wire("a-1"), "paper-10", "0.1", "651", FakeGateway.TIME + 502)
            deals.replaceAll { it.copy(time = it.time - 26) }
        }
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        val half = Position(symbol, BigDecimal("0.1"), BigDecimal("650"), FakeGateway.TIME + 502)
        val whole = Position(symbol, BigDecimal("0.2"), BigDecimal("650.5"), FakeGateway.TIME + 502)
        a.bus.publish(accounted("a-1", FakeGateway.TIME + 502, null, half))
        a.bus.publish(accounted("a-1", FakeGateway.TIME + 502, half, whole))

        val deals = broker.deals(0, Long.MAX_VALUE)

        assertThat(deals.map { it.dealTicket }).containsExactly("paper-9", "paper-10")
        assertThat(deals.map { it.entry }).containsExactly("IN", "IN")
        assertThat(deals.map { it.positionTicket }).containsOnly("$symbol#${FakeGateway.TIME + 502}")
        assertThat(deals).allSatisfy { assertThat(it.strategyId).isEqualTo("a") }
    }

    @Test
    fun `a fill accounted under another order id is not taken for this deal`() {
        val a = Strategy()
        val broker = broker(session(), a, "a")
        broker.submit(market("a-1", "a"))
        await { a.of<BrokerEvent.OrderAccepted>().isNotEmpty() }
        fake.act { fill(wire("a-1"), "paper-9", "0.1", "650", FakeGateway.TIME) }
        await { a.of<BrokerEvent.OrderFilled>().isNotEmpty() }
        a.bus.publish(accounted("a-2", FakeGateway.TIME, null, Position(symbol, BigDecimal("0.1"), BigDecimal("650"))))

        assertThat(broker.deals(0, Long.MAX_VALUE)).isEmpty()
    }

    private fun accounted(
        orderId: String,
        time: Long,
        before: Position?,
        after: Position?,
    ) = FillAccountedEvent(
        orderId,
        "a",
        symbol,
        "$orderId:1",
        1,
        null,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "USDC",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        "USDC",
        BigDecimal.ZERO,
        BigDecimal.ZERO,
        null,
        null,
        null,
        BigDecimal.ONE,
        before,
        after,
        before,
        after,
        reducedExposure = false,
        partial = false,
        executedAt = time,
    )
}
