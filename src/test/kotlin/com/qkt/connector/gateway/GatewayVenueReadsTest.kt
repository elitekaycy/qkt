package com.qkt.connector.gateway

import com.qkt.broker.CloseReason
import com.qkt.broker.VenueAccountId
import com.qkt.broker.VenueIdentity
import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A gateway account's deals carry what qkt's accounting booked for each fill: the leg (scale-in, partial
 * close, reversal), the qkt-minted position id that changes when the position crosses zero, and the gross
 * P&L qkt realized. A fill qkt never booked is left out, and a young one waits for the books.
 */
internal class GatewayVenueReadsTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val symbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val fake = FakeGateway(listOf(code))
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1)
    private val legs = GatewayFillLegs()
    private val venue = VenueIdentity("vgp", "fake", "DERIBIT", VenueAccountId.vgp("fake", "7"))
    private var held: Position? = null
    private val reads =
        GatewayVenueReads(
            venue,
            GatewayDirectReads("DERIBIT", client),
            client,
            GatewaySymbols("DERIBIT:"),
            legs,
            object : PositionProvider {
                override fun positionFor(symbol: String) = held?.takeIf { it.symbol == symbol }

                override fun allPositions() = listOfNotNull(held).associateBy { it.symbol }
            },
            "a",
            { id -> if (id.startsWith("dsl-a-")) "a" else null },
        )

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `deals state leg, position and realized across scale-in, partial close and reversal`() {
        book("dsl-a-1", "buy", "1", "100", 1_000, null, pos("1", 1_000), "0")
        book("dsl-a-2", "buy", "1", "110", 2_000, pos("1", 1_000), pos("2", 1_000), "0")
        book("dsl-a-3", "sell", "1", "120", 3_000, pos("2", 1_000), pos("1", 1_000), "15")
        book("dsl-a-4", "sell", "2", "130", 4_000, pos("1", 1_000), pos("-1", 4_000), "25")
        book("dsl-a-4-sl", "buy", "1", "125", 5_000, pos("-1", 4_000), null, "5")

        val deals = reads.deals(0, 10_000, nowMs = 1_000_000)

        assertThat(deals.map { it.entry }).containsExactly("IN", "IN", "OUT", "INOUT", "OUT")
        assertThat(deals.map { it.positionTicket })
            .containsExactly("$symbol#1000", "$symbol#1000", "$symbol#1000", "$symbol#1000", "$symbol#4000")
        assertThat(deals[3].nextPositionTicket).isEqualTo("$symbol#4000")
        assertThat(deals.map { it.profit.toPlainString() }).containsExactly("0", "0", "15", "25", "5")
        assertThat(deals.map { it.closeReason })
            .containsExactly(null, null, CloseReason.SIGNAL, CloseReason.SIGNAL, CloseReason.STOP)
        assertThat(deals).allSatisfy {
            assertThat(it.broker).isEqualTo("DERIBIT")
            assertThat(it.symbol).isEqualTo(symbol)
            assertThat(it.strategyId).isEqualTo("a")
            assertThat(it.realizedSource).isEqualTo("engine")
        }
    }

    @Test
    fun `a paid cost is a negative scalar and a positive typed cost`() {
        book(
            "dsl-a-1",
            "buy",
            "1",
            "100",
            1_000,
            null,
            pos("1", 1_000),
            "0",
            listOf(WireCost("commission", "0.5", "USDC")),
        )

        val deal = reads.deals(0, 10_000, nowMs = 1_000_000).single()

        assertThat(deal.commission).isEqualByComparingTo("-0.5")
        assertThat(
            deal.costs
                .single()
                .amount.amount,
        ).isEqualByComparingTo("0.5")
    }

    @Test
    fun `a fill qkt never booked is left out, and a young one waits with every deal after it`() {
        fake.venue.deals += WireFill("manual.x", null, "f-manual", code, "buy", "1", "90", 500)
        book("dsl-a-1", "buy", "1", "100", 1_000, null, pos("1", 1_000), "0")
        fake.venue.deals += WireFill("dsl-a-2.y", null, "f-young", code, "buy", "1", "100", 990_000)
        book("dsl-a-3", "sell", "1", "100", 995_000, pos("1", 1_000), null, "0")

        val deals = reads.deals(0, 1_000_000, nowMs = 1_000_000)

        assertThat(deals.map { it.dealTicket }).containsExactly("f-dsl-a-1")
    }

    @Test
    fun `a netting position's ticket is qkt's id for it, owned by the strategy that booked it`() {
        book("dsl-a-1", "buy", "1", "100", 1_000, null, pos("1", 1_000), "0")

        val position = reads.positions().single()

        assertThat(position.ticket).isEqualTo("$symbol#1000")
        assertThat(position.symbol).isEqualTo(symbol)
        assertThat(position.strategyId).isEqualTo("a")
    }

    @Test
    fun `after a restart the position's id comes from the session's own position`() {
        fake.venue.deals += WireFill("dsl-a-1.x", null, "f1", code, "buy", "1", "100", 1_000)
        held = pos("1", 777)

        assertThat(reads.positions().single().ticket).isEqualTo("$symbol#777")
    }

    @Test
    fun `the account states its venue identity under the account's label`() {
        val account = reads.account()

        assertThat(account.broker).isEqualTo("DERIBIT")
        assertThat(account.login).isEqualTo("7")
        assertThat(account.venue?.accountId).isEqualTo(VenueAccountId.vgp("fake", "7"))
    }

    private fun pos(
        qty: String,
        openedAt: Long,
    ) = Position(symbol, BigDecimal(qty), BigDecimal("100"), openedAt)

    /** The venue executes [orderId] and qkt's accounting books it, moving the account from [before] to [after]. */
    private fun book(
        orderId: String,
        side: String,
        qty: String,
        price: String,
        time: Long,
        before: Position?,
        after: Position?,
        realized: String,
        costs: List<WireCost> = emptyList(),
    ) {
        fake.venue.deals += WireFill("$orderId.k", "v-$orderId", "f-$orderId", code, side, qty, price, time, costs)
        legs.booked(orderId, "f-$orderId")
        legs.record(accounted(orderId, time, before, after, BigDecimal(realized)))
    }

    private fun accounted(
        orderId: String,
        time: Long,
        before: Position?,
        after: Position?,
        realized: BigDecimal,
    ) = FillAccountedEvent(
        orderId = orderId,
        strategyId = "a",
        symbol = symbol,
        fillSliceId = "$orderId:1",
        sourceFillSequenceId = 1,
        cumulativeFilled = null,
        modeledCommissionAccount = BigDecimal.ZERO,
        venueCostsAccount = BigDecimal.ZERO,
        totalCostsAccount = BigDecimal.ZERO,
        accountNativeRealized = realized,
        strategyNativeRealized = realized,
        nativeCurrency = "USDC",
        grossAccountRealized = realized,
        grossStrategyAccountRealized = realized,
        accountCurrency = "USDC",
        netAccountRealized = realized,
        netStrategyAccountRealized = realized,
        conversionRate = null,
        conversionTimestampMs = null,
        conversionSource = null,
        contractSize = BigDecimal.ONE,
        accountPositionBefore = before,
        accountPositionAfter = after,
        strategyPositionBefore = before,
        strategyPositionAfter = after,
        reducedExposure = false,
        partial = false,
        executedAt = time,
    )
}
