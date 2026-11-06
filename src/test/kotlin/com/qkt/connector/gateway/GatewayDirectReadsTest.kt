package com.qkt.connector.gateway

import com.qkt.candles.TimeWindow
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/** Deals and bars of a VGP v1 gateway as `qkt bot` prints them. */
class GatewayDirectReadsTest {
    private val perp = "SOL_USDC-PERPETUAL"
    private val fake =
        FakeGateway(listOf(perp)).apply {
            instruments[perp] = WireInstrument(perp, "perpetual", "USDC", "1", "0.001", "0.1", "0.1")
        }
    private val venue =
        GatewayDirectVenue(
            name = "deribit",
            prefix = "DERIBIT:",
            client = GatewayClient(fake.url, "k", httpTimeoutMs = 2_000, retryAttempts = 0),
            baseUrl = fake.url,
            apiKey = "k",
            pollTimeoutMs = 2_000,
            pollMs = 20,
        )

    @AfterEach
    fun stop() = fake.shutdown()

    private fun workingLimit(): String {
        val limit =
            OrderRequest.Limit(
                "bot-a-2",
                "DERIBIT:SOL_USDC_PERPETUAL",
                Side.BUY,
                BigDecimal("2"),
                BigDecimal("104"),
                TimeInForce.GTC,
                1_000L,
                "a",
            )
        venue.place(limit, null, null, "bot-a-2")
        return fake.submits.single().clientOrderId
    }

    @Test
    fun `a deal sums its costs into commission and names the order that made it`() {
        val id = workingLimit()
        fake.act {
            fill(
                id,
                "f1",
                "1",
                "104",
                FakeGateway.TIME,
                listOf(WireCost("fee", "0.25", "USDC"), WireCost("fee", "0.5", "USDC")),
            )
            fill(id, "f2", "1", "103.9", FakeGateway.TIME + 1)
        }

        val deals = venue.deals(FakeGateway.TIME - 1_000L, FakeGateway.TIME + 1_000L)

        assertThat(deals.map { it.dealTicket }).containsExactly("f1", "f2")
        val first = deals[0]
        assertThat(first.symbol).isEqualTo(perp)
        assertThat(first.side).isEqualTo("BUY")
        assertThat(first.lots).isEqualByComparingTo("1")
        assertThat(first.price).isEqualByComparingTo("104")
        assertThat(first.commission).isEqualByComparingTo("0.75")
        assertThat(first.comment).isEqualTo(id)
        assertThat(first.positionTicket).isNull()
        assertThat(first.entry).isNull()
        assertThat(deals[1].commission).isNull()
        assertThat(deals[1].timeMs).isEqualTo(FakeGateway.TIME + 1)
    }

    @Test
    fun `deals outside the window are not reported`() {
        val id = workingLimit()
        fake.act { fill(id, "f1", "2", "104", FakeGateway.TIME) }

        assertThat(venue.deals(0L, 1_000L)).isEmpty()
    }

    @Test
    fun `bars keep the newest closed ones and drop the bar still forming`() {
        val minute = 60_000L
        val now = 1_800_000_000_000L / minute * minute + 30_000L
        val first = now - 30_000L - 4 * minute
        fake.barsPage = 10
        fake.bars[perp to minute] =
            (0..4).map { i ->
                val start = first + i * minute
                WireBar(start, "10$i", "11$i", "9$i", "10$i.5", "$i")
            }

        val bars = venue.bars("DERIBIT:SOL_USDC_PERPETUAL", TimeWindow(minute), count = 2, nowMs = now)

        assertThat(bars.map { it.startTime }).containsExactly(first + 2 * minute, first + 3 * minute)
        val last = bars.last()
        assertThat(last.symbol).isEqualTo("DERIBIT:SOL_USDC_PERPETUAL")
        assertThat(last.open).isEqualByComparingTo("103")
        assertThat(last.high).isEqualByComparingTo("113")
        assertThat(last.low).isEqualByComparingTo("93")
        assertThat(last.close).isEqualByComparingTo("103.5")
        assertThat(last.volume).isEqualByComparingTo("3")
        assertThat(last.endTime).isEqualTo(first + 4 * minute)
    }
}
