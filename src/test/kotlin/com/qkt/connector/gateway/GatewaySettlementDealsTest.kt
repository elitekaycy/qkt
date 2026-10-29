package com.qkt.connector.gateway

import com.qkt.broker.CloseReason
import com.qkt.broker.VenueAccountId
import com.qkt.broker.VenueIdentity
import com.qkt.common.Side
import com.qkt.events.FillAccountedEvent
import com.qkt.positions.Position
import com.qkt.positions.PositionProvider
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test

/**
 * A contract held to expiry closes by the venue's settlement, which no fill carries: the account's deals end
 * its position there, with what qkt booked for it. Without it the bot2 soak's expired call stayed open in
 * Insights for good while its −0.40 was counted in the strategy's realized P&L.
 */
internal class GatewaySettlementDealsTest {
    private val code = "BTC_USDC-29OCT26-80500-C"
    private val symbol = "DERIBIT:BTC_USDC_29OCT26_80500_C"
    private val fake = FakeGateway(listOf(code))
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1)
    private val legs = GatewayFillLegs()
    private val reads =
        GatewayVenueReads(
            VenueIdentity("vgp", "fake", "DERIBIT", VenueAccountId.vgp("fake", "7")),
            GatewayDirectReads("DERIBIT", client),
            client,
            GatewaySymbols("DERIBIT:"),
            legs,
            object : PositionProvider {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions() = emptyMap<String, Position>()
            },
            "a",
            { id -> id.substringAfter("dsl-").substringBefore("-").takeIf { id.startsWith("dsl-") } },
        )

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `an option held to expiry ends its position at the settlement with what qkt booked`() {
        fill("dsl-a-1", "a", "buy", "1", "40", 1_000, null, pos("1", 1_000))
        settle("0", 9_000, "a" to (pos("1", 1_000) to "-40"))

        val deals = reads.deals(0, 10_000, nowMs = 1_000_000)

        assertThat(deals.map { it.entry }).containsExactly("IN", "OUT")
        val close = deals.last()
        assertThat(close.dealTicket).isEqualTo("settle:$code:a")
        assertThat(close.positionTicket).isEqualTo(deals.first().positionTicket).isEqualTo("$symbol#1000")
        assertThat(close.side to close.qty.toPlainString()).isEqualTo(Side.SELL to "1")
        assertThat(close.price).isEqualByComparingTo("0")
        assertThat(close.profit).isEqualByComparingTo("-40")
        assertThat(close.closeReason).isEqualTo(CloseReason.EXPIRY)
        assertThat(close.symbol to close.strategyId).isEqualTo(symbol to "a")
        assertThat(close.ts).isEqualTo(9_000)
    }

    @Test
    fun `each strategy that held the contract gets its own close, the short one bought back`() {
        fill("dsl-a-1", "a", "buy", "2", "40", 1_000, null, pos("2", 1_000))
        fill("dsl-b-1", "b", "sell", "1", "41", 2_000, pos("2", 1_000), pos("1", 1_000))
        settle("120", 9_000, "a" to (pos("2", 1_000) to "160"), "b" to (pos("-1", 2_000) to "-79"))

        val closes = reads.deals(0, 10_000, nowMs = 1_000_000).filter { it.closeReason == CloseReason.EXPIRY }

        assertThat(closes.map { Triple(it.strategyId, it.side, it.qty.toPlainString()) })
            .containsExactly(Triple("a", Side.SELL, "2"), Triple("b", Side.BUY, "1"))
        assertThat(closes.map { it.profit.toPlainString() }).containsExactly("160", "-79")
    }

    @Test
    fun `a settlement not on the books yet holds back itself and every deal after it`() {
        fill("dsl-a-1", "a", "buy", "1", "40", 1_000, null, pos("1", 1_000))
        fake.venue.settlements += WireSettlement(code, "0", 9_000)
        fill("dsl-a-2", "a", "buy", "1", "40", 9_500, pos("1", 1_000), pos("2", 1_000))

        val young = reads.deals(0, 10_000, nowMs = 9_600)
        val old = reads.deals(0, 10_000, nowMs = 1_000_000)

        assertThat(young.map { it.ts }).containsExactly(1_000L)
        // Once past the grace, a settlement qkt never booked (a contract it did not hold) is not the account's.
        assertThat(old.map { it.ts }).containsExactly(1_000L, 9_500L)
    }

    private fun pos(
        quantity: String,
        openedAt: Long,
    ) = Position(symbol, BigDecimal(quantity), BigDecimal("40"), openedAt = openedAt)

    private fun fill(
        orderId: String,
        strategyId: String,
        side: String,
        qty: String,
        price: String,
        time: Long,
        before: Position?,
        after: Position?,
    ) {
        fake.venue.deals +=
            WireFill("$orderId.k", "v-$orderId", "f-$orderId", code, side, qty, price, time, emptyList())
        legs.booked(orderId, "f-$orderId")
        legs.record(accounted(orderId, strategyId, before, after, before, after, BigDecimal.ZERO, time))
    }

    /** The venue settles the contract at [price]; qkt closes each holder (strategy to its holding and realized). */
    private fun settle(
        price: String,
        time: Long,
        vararg holders: Pair<String, Pair<Position, String>>,
    ) {
        fake.venue.settlements += WireSettlement(code, price, time)
        var account: Position? =
            holders.fold(BigDecimal.ZERO) { s, h -> s + h.second.first.quantity }.let {
                Position(symbol, it, BigDecimal("40"), openedAt = 1_000)
            }
        for ((strategyId, held) in holders) {
            val (position, realized) = held
            val after = account!!.quantity - position.quantity
            val accountAfter = if (after.signum() == 0) null else account.copy(quantity = after)
            legs.record(
                accounted(
                    "settle:$symbol:$strategyId",
                    strategyId,
                    account,
                    accountAfter,
                    position,
                    null,
                    BigDecimal(realized),
                    time,
                ),
            )
            account = accountAfter ?: Position(symbol, BigDecimal.ZERO, BigDecimal("40"), openedAt = 1_000)
        }
    }

    private fun accounted(
        orderId: String,
        strategyId: String,
        accountBefore: Position?,
        accountAfter: Position?,
        strategyBefore: Position?,
        strategyAfter: Position?,
        realized: BigDecimal,
        time: Long,
    ) = FillAccountedEvent(
        orderId = orderId,
        strategyId = strategyId,
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
        accountPositionBefore = accountBefore,
        accountPositionAfter = accountAfter,
        strategyPositionBefore = strategyBefore,
        strategyPositionAfter = strategyAfter,
        reducedExposure = true,
        partial = false,
        executedAt = time,
    )
}
