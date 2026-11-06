package com.qkt.connector.gateway

import com.qkt.broker.BrokerDeal
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
 * A deal's scalar `commission`/`fee` is in the account currency (Insights books it as such, #1662). A spot fee
 * the venue takes in the coin bought (Alpaca ETHUSD, Bybit spot) is priced at the fill, the rule qkt's own
 * accounting books it by, and the typed costs keep it as paid. A cost in a currency qkt cannot price from the
 * fill is never added to the scalar as if it were account currency.
 */
internal class GatewayVenueReadsCoinFeeTest {
    private val fake = FakeGateway(listOf("ETHUSD", "ETHUSDT"))
    private val client = GatewayClient(fake.url, "k", httpTimeoutMs = 500, retryAttempts = 1)
    private val legs = GatewayFillLegs()
    private val reads =
        GatewayVenueReads(
            VenueIdentity("vgp", "fake", "SPOT", VenueAccountId.vgp("fake", "7")),
            GatewayDirectReads("SPOT", client),
            client,
            GatewaySymbols("SPOT:"),
            legs,
            object : PositionProvider {
                override fun positionFor(symbol: String): Position? = null

                override fun allPositions() = emptyMap<String, Position>()
            },
            "a",
            { null },
        )

    @AfterEach
    fun stop() = fake.shutdown()

    @Test
    fun `an Alpaca ETHUSD fee in ETH is a USD commission at the fill price`() {
        val deal = dealOf("ETHUSD", "2247.57", WireCost("commission", "0.0000125", "ETH"))

        val paid = deal.costs.single().amount
        assertThat(deal.commission).isEqualByComparingTo("-0.028094625")
        assertThat(paid.amount).isEqualByComparingTo("0.0000125")
        assertThat(paid.currency).isEqualTo("ETH")
    }

    @Test
    fun `a Bybit spot ETHUSDT fee in ETH is a commission in the quote at the fill price`() {
        val deal = dealOf("ETHUSDT", "2315.29", WireCost("commission", "0.00001", "ETH"))

        assertThat(deal.commission).isEqualByComparingTo("-0.0231529")
    }

    @Test
    fun `a coin fee and a quote fee add up only once both are in the quote`() {
        val deal =
            dealOf(
                "ETHUSD",
                "2000",
                WireCost("commission", "0.0001", "ETH"),
                WireCost("commission", "0.5", "USD"),
                WireCost("exchange_fee", "0.01", "ETH"),
            )

        assertThat(deal.commission).isEqualByComparingTo("-0.7")
        assertThat(deal.fee).isEqualByComparingTo("-20")
    }

    @Test
    fun `a cost in a currency the fill cannot price stays out of the scalar and in the typed costs`() {
        val deal =
            dealOf(
                "ETHUSD",
                "2000",
                WireCost("commission", "3", "BNB"),
                WireCost("commission", "0.5", "USD"),
            )

        assertThat(deal.commission).isEqualByComparingTo("-0.5")
        assertThat(deal.costs.map { it.amount.currency }).containsExactly("BNB", "USD")
    }

    /** The venue fills a buy of [code] at [price] with [costs] and qkt's accounting books it; its deal. */
    private fun dealOf(
        code: String,
        price: String,
        vararg costs: WireCost,
    ): BrokerDeal {
        fake.venue.deals += WireFill("dsl-a-1.k", "v-1", "f-1", code, "buy", "0.005", price, 1_000, costs.toList())
        legs.booked("dsl-a-1", "f-1")
        legs.record(accounted("SPOT:$code"))
        return reads.deals(0, 10_000, nowMs = 1_000_000).single()
    }

    private fun accounted(symbol: String): FillAccountedEvent {
        val after = Position(symbol, BigDecimal("0.005"), BigDecimal("2000"), 1_000)
        return FillAccountedEvent(
            orderId = "dsl-a-1",
            strategyId = "a",
            symbol = symbol,
            fillSliceId = "dsl-a-1:1",
            sourceFillSequenceId = 1,
            cumulativeFilled = null,
            modeledCommissionAccount = BigDecimal.ZERO,
            venueCostsAccount = BigDecimal.ZERO,
            totalCostsAccount = BigDecimal.ZERO,
            accountNativeRealized = BigDecimal.ZERO,
            strategyNativeRealized = BigDecimal.ZERO,
            nativeCurrency = "USD",
            grossAccountRealized = BigDecimal.ZERO,
            grossStrategyAccountRealized = BigDecimal.ZERO,
            accountCurrency = "USD",
            netAccountRealized = BigDecimal.ZERO,
            netStrategyAccountRealized = BigDecimal.ZERO,
            conversionRate = null,
            conversionTimestampMs = null,
            conversionSource = null,
            contractSize = BigDecimal.ONE,
            accountPositionBefore = null,
            accountPositionAfter = after,
            strategyPositionBefore = null,
            strategyPositionAfter = after,
            reducedExposure = false,
            partial = false,
            executedAt = 1_000,
        )
    }
}
