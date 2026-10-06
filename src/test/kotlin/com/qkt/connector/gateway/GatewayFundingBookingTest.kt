package com.qkt.connector.gateway

import com.qkt.app.FundingBooking
import com.qkt.bus.EventBus
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.common.SystemClock
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.events.FillAccountingKind
import com.qkt.events.FundingCharged
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A gateway account's perpetual funding reaches the strategy holding it as financing, and a restart books it once. */
internal class GatewayFundingBookingTest : GatewayHarness() {
    private val perp = "BTC_USDC-PERPETUAL"
    private val perpSymbol = "DERIBIT:BTC_USDC_PERPETUAL"
    private val clock = SystemClock()
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String) = BigDecimal("65000")
        }
    private val disk =
        object : FundingPersistence {
            val byOwner = ConcurrentHashMap<String, PersistedFunding>()

            override fun saveFunding(
                ownerId: String,
                funding: PersistedFunding,
            ) {
                byOwner[ownerId] = funding
            }

            override fun loadFunding(ownerId: String) = byOwner[ownerId]
        }

    /** One run of strategy `s1` holding 0.2 of the perpetual, until the gateway hands it its funding. */
    private fun run(): List<CostIncurred> {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val positions = StrategyPositionTracker()
        positions.applyFill(
            BrokerEvent.OrderFilled("o1", null, perpSymbol, Side.BUY, BigDecimal("65000"), BigDecimal("0.2"), "s1"),
            LegIntent.Open("leg-1", LegRole.PRIMARY),
        )
        FundingBooking(bus, positions, disk, prices, clock).bind(listOf("s1"))
        val costs = CopyOnWriteArrayList<CostIncurred>()
        val heard = CopyOnWriteArrayList<FundingCharged>()
        bus.subscribe<CostIncurred> { costs += it }
        bus.subscribe<FundingCharged> { heard += it }

        GatewayBroker(session(setOf("s1")), bus, clock, positions.account, "s1").watchBookedLegs { emptyList() }

        await { heard.isNotEmpty() }
        return costs.toList()
    }

    private fun funded() {
        fake.codes = listOf(code, perp)
        fake.capabilities = listOf("funding")
        disk.saveFunding("s1", PersistedFunding(clock.now() - 2 * HOUR_MS, emptyMap()))
        fake.quiet = true
        fake.act {
            place(WireSubmit("held-1", perp, "buy", "market", "0.2", null, null, "gtc", false))
            fill("held-1", "f0", "0.2", "65000", clock.now() - 2 * HOUR_MS)
            fund(WireFunding("tx-1", perp, "1.2", "USDC", "0.2", clock.now() - HOUR_MS))
        }
        fake.quiet = false
    }

    @Test
    fun `the strategy holding the funded perpetual is charged the funding as financing`() {
        funded()

        val costs = run()

        assertThat(costs.map { it.strategyId to it.symbol }).containsExactly("s1" to perpSymbol)
        assertThat(costs.single().amount).isEqualByComparingTo("1.2")
        assertThat(costs.single().kind).isEqualTo(FillAccountingKind.FINANCING)
    }

    @Test
    fun `the same funding handed again after a restart is not booked again`() {
        funded()
        run()

        val afterRestart = run()

        assertThat(afterRestart).isEmpty()
    }

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
