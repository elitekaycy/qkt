package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.events.FillAccountedEvent
import com.qkt.events.FillAccountingKind
import com.qkt.events.FundingCharged
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A venue's perpetual funding, booked by the strategies of one session, each its own part, once. */
class FundingBookingTest {
    private val day = 86_400_000L
    private val clock = FixedClock(100 * day)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val costs = mutableListOf<CostIncurred>()
    private val perp = "DERIBIT:SOL_USDC_PERPETUAL"
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String) = BigDecimal("121.5")
        }

    private val saved =
        object : FundingPersistence {
            val byOwner = HashMap<String, PersistedFunding>()

            override fun saveFunding(
                ownerId: String,
                funding: PersistedFunding,
            ) {
                byOwner[ownerId] = funding
            }

            override fun loadFunding(ownerId: String) = byOwner[ownerId]
        }

    init {
        bus.subscribe<CostIncurred> { costs += it }
    }

    private fun bind(vararg strategies: String) = FundingBooking(bus, positions, saved, prices, clock).bind(strategies.toList())

    private fun hold(
        strategy: String,
        side: Side,
        quantity: String,
    ) = positions.applyFill(
        BrokerEvent.OrderFilled("o-$strategy", null, perp, side, BigDecimal("120"), BigDecimal(quantity), strategy),
        LegIntent.Open("leg-$strategy", LegRole.PRIMARY),
    )

    private fun funding(
        id: String,
        amount: String,
        basis: String,
        atMs: Long = clock.now() + 1,
    ) = FundingCharged(id, perp, BigDecimal(amount), "USDC", BigDecimal(basis), atMs)

    @Test
    fun `each holder books amount times its signed holding over the basis, as financing at the last price`() {
        bind("a", "b", "c")
        hold("a", Side.BUY, "150")
        hold("b", Side.SELL, "50")

        bus.publish(funding("tx-1", "1.2", "100"))

        assertThat(costs.map { it.strategyId to it.amount.stripTrailingZeros().toPlainString() })
            .containsExactly("a" to "1.8", "b" to "-0.6")
        assertThat(costs).allMatch {
            it.kind == FillAccountingKind.FINANCING && it.symbol == perp && it.referencePrice == BigDecimal("121.5")
        }
    }

    @Test
    fun `a part of the basis no strategy here holds, another tool's position, is left unbooked`() {
        bind("a")
        hold("a", Side.BUY, "50")

        bus.publish(funding("tx-1", "-2", "200"))

        assertThat(costs.single().amount.stripTrailingZeros().toPlainString()).isEqualTo("-0.5")
    }

    @Test
    fun `a record heard twice, or funded before the session first started, is not booked again`() {
        bind("a")
        hold("a", Side.BUY, "10")

        bus.publish(funding("tx-1", "1", "10"))
        bus.publish(funding("tx-1", "1", "10"))
        bus.publish(funding("tx-0", "1", "10", atMs = clock.now() - 1))

        assertThat(costs.map { it.reason }).containsExactly("funding tx-1")
    }

    @Test
    fun `a restart keeps what was booked, and forgets ids older than any replay`() {
        bind("a")
        hold("a", Side.BUY, "10")
        bus.publish(funding("tx-1", "1", "10"))
        val since = saved.byOwner.getValue("a").sinceMs

        clock.advanceTo(clock.now() + 9 * day)
        val restarted = EventBus(clock, MonotonicSequenceGenerator()).also { it.subscribe<CostIncurred> { c -> costs += c } }
        FundingBooking(restarted, positions, saved, prices, clock).bind(listOf("a"))
        restarted.publish(funding("tx-1", "1", "10", atMs = since + 1))
        restarted.publish(funding("tx-2", "1", "10"))

        assertThat(saved.byOwner.getValue("a").sinceMs).isEqualTo(since)
        assertThat(saved.byOwner.getValue("a").booked.keys).containsExactly("tx-2")
        assertThat(costs.map { it.reason }).containsExactly("funding tx-1", "funding tx-2")
    }

    private fun closed(
        strategy: String,
        before: String,
    ) = FillAccountedEvent(
        orderId = "c-$strategy",
        strategyId = strategy,
        symbol = perp,
        fillSliceId = "c-$strategy:1",
        sourceFillSequenceId = 1L,
        cumulativeFilled = null,
        modeledCommissionAccount = BigDecimal.ZERO,
        venueCostsAccount = BigDecimal.ZERO,
        totalCostsAccount = BigDecimal.ZERO,
        accountNativeRealized = BigDecimal.ZERO,
        strategyNativeRealized = BigDecimal.ZERO,
        nativeCurrency = "USDC",
        grossAccountRealized = BigDecimal.ZERO,
        grossStrategyAccountRealized = BigDecimal.ZERO,
        accountCurrency = "USDC",
        netAccountRealized = BigDecimal.ZERO,
        netStrategyAccountRealized = BigDecimal.ZERO,
        conversionRate = null,
        conversionTimestampMs = null,
        conversionSource = null,
        contractSize = BigDecimal.ONE,
        accountPositionBefore = null,
        accountPositionAfter = null,
        strategyPositionBefore = Position(perp, BigDecimal(before), BigDecimal("120")),
        strategyPositionAfter = null,
        reducedExposure = true,
        partial = false,
    )

    @Test
    fun `funding realized once the positions are gone is shared by what each closed since, then forgotten`() {
        bind("a", "b")
        bus.publish(closed("a", "30"))
        bus.publish(closed("b", "-10"))

        bus.publish(funding("tx-day", "4", "0"))
        bus.publish(funding("tx-next", "4", "0"))

        assertThat(costs.map { it.strategyId to it.amount.stripTrailingZeros().toPlainString() })
            .containsExactly("a" to "6", "b" to "-2")
        assertThat(saved.byOwner.getValue("a").closed).isEmpty()
    }

    @Test
    fun `a record no strategy here held or closed since the symbol's last funding is not booked`() {
        bind("a")

        bus.publish(funding("tx-1", "1", "0"))
        bus.publish(funding("tx-2", "1", "10"))

        assertThat(costs).isEmpty()
    }
}
