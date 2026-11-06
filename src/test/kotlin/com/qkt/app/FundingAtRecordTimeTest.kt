package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.events.FundingCharged
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * A venue that realizes funding on a trade (Deribit, #1661) names the moment it accrued, just before the trade that
 * realized it: each strategy books by what it held then, not by what it holds when the record is heard.
 */
class FundingAtRecordTimeTest {
    private val clock = FixedClock(100 * 86_400_000L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val costs = mutableListOf<CostIncurred>()
    private val perp = "DERIBIT:ETH_USDC_PERPETUAL"
    private val fundedAt = clock.now() + 10_000
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String) = BigDecimal("2225")
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

    private fun bind(vararg strategies: String) =
        FundingBooking(bus, positions, saved, prices, clock).bind(strategies.toList())

    private fun hold(
        strategy: String,
        side: Side,
        quantity: String,
    ) = positions.applyFill(
        BrokerEvent.OrderFilled("o-$strategy", null, perp, side, BigDecimal("2225"), BigDecimal(quantity), strategy),
        LegIntent.Open("leg-$strategy", LegRole.PRIMARY),
    )

    /** The trade that realized the record, accounted after the record's venue time (qkt hears it later). */
    private fun realizing(
        strategy: String,
        before: String,
        after: String?,
    ) = bus.publish(movingFill(strategy, perp, before, after, fundedAt + 4_000))

    private fun funding(
        id: String,
        amount: String,
        basis: String,
    ) = FundingCharged(id, perp, BigDecimal(amount), "USDC", BigDecimal(basis), fundedAt)

    private fun booked() = costs.map { it.strategyId to it.amount.stripTrailingZeros().toPlainString() }

    @Test
    fun `a strategy that exited with the realizing trade books its share of what it held then`() {
        bind("k46", "o13")
        bus.publish(movingFill("k46", perp, "0", "-1.4", fundedAt - 60_000))
        hold("o13", Side.SELL, "0.03")
        realizing("k46", "-1.4", null)

        bus.publish(funding("tx-169214407", "-1.43", "-1.43"))

        assertThat(booked()).containsExactlyInAnyOrder("k46" to "-1.4", "o13" to "-0.03")
    }

    @Test
    fun `a strategy that entered with the realizing trade, or was flat before it, books none of it`() {
        bind("o01", "o11", "o13")
        bus.publish(closingFill("o01", perp, "-0.03"))
        hold("o13", Side.SELL, "0.03")
        hold("o11", Side.BUY, "0.03")
        realizing("o11", "0", "0.03")

        bus.publish(funding("tx-169224002", "-0.00064468", "-0.03"))

        assertThat(booked()).containsExactly("o13" to "-0.00064468")
    }

    @Test
    fun `sessions sharing an account book together exactly the record, each by its holding at its time`() {
        bind("k30")
        bind("k33")
        bind("k46")
        bind("o11", "o13")
        hold("k30", Side.SELL, "1.4")
        hold("k33", Side.SELL, "1.4")
        hold("o11", Side.BUY, "0.03")
        hold("o13", Side.SELL, "0.03")
        hold("k46", Side.SELL, "1.4")
        realizing("k46", "0", "-1.4")

        bus.publish(funding("tx-169183374", "0.00819756", "-2.8"))

        assertThat(costs.map { it.strategyId }).containsExactlyInAnyOrder("k30", "k33", "o11", "o13")
        assertThat(costs.fold(BigDecimal.ZERO) { sum, c -> sum.add(c.amount) }).isEqualByComparingTo("0.00819756")
    }

    @Test
    fun `a positionless record on a basis of zero is booked by no session, not by what each closed since`() {
        bind("o01")
        bind("o10")
        bus.publish(closingFill("o01", perp, "-0.03"))
        bus.publish(closingFill("o10", perp, "-0.02"))

        bus.publish(
            FundingCharged("tx-0", perp, BigDecimal("-1"), "USDC", BigDecimal.ZERO, fundedAt, positionless = true),
        )

        assertThat(costs).isEmpty()
    }
}
