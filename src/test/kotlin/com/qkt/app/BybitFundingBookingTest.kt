package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.connector.bybit.FakeBybitClient
import com.qkt.connector.bybit.linear.BybitLinearBroker
import com.qkt.connector.bybit.linear.BybitLinearFundingTest.Companion.BYBIT_DOC_FUNDING
import com.qkt.events.BrokerEvent
import com.qkt.events.CostIncurred
import com.qkt.events.FillAccountingKind
import com.qkt.execution.LegIntent
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.persistence.FundingPersistence
import com.qkt.persistence.PersistedFunding
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Bybit linear funding reaches the strategy holding the perpetual, and a restart books it once. */
class BybitFundingBookingTest {
    private val clock = FixedClock(1_682_550_000_000L)
    private val perp = "BYBIT_LINEAR:BTCUSDT"
    private val prices =
        object : MarketPriceProvider {
            override fun lastPrice(symbol: String) = BigDecimal("28399.9")
        }
    private val disk =
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

    /** One session: a short of 0.011 held by `s1`, the broker started; returns the costs it booked. */
    private fun session(): List<CostIncurred> {
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val positions = StrategyPositionTracker()
        positions.applyFill(
            BrokerEvent.OrderFilled("o1", null, perp, Side.SELL, BigDecimal("28000"), BigDecimal("0.011"), "s1"),
            LegIntent.Open("leg-1", LegRole.PRIMARY),
        )
        FundingBooking(bus, positions, disk, prices, clock).bind(listOf("s1"))
        val costs = mutableListOf<CostIncurred>()
        bus.subscribe<CostIncurred> { costs += it }
        val client = FakeBybitClient()
        val empty = """{"retCode":0,"retMsg":"OK","result":{"list":[]}}"""
        listOf("/v5/order/realtime", "/v5/account/wallet-balance", "/v5/position/list").forEach {
            client.responses[it] =
                empty
        }
        client.responses["/v5/execution/list"] =
            """{"retCode":0,"retMsg":"OK","result":{"list":[$BYBIT_DOC_FUNDING]}}"""
        BybitLinearBroker(client, bus, clock, positions.account).shutdown()
        return costs
    }

    @Test
    fun `the short holding the funded position is charged the execution's fee as financing`() {
        disk.saveFunding("s1", PersistedFunding(clock.now(), emptyMap()))
        clock.time = 1_682_560_000_000L

        val costs = session()

        assertThat(costs.map { it.strategyId to it.amount.stripTrailingZeros().toPlainString() })
            .containsExactly("s1" to "0.6364003")
        assertThat(costs.single().kind).isEqualTo(FillAccountingKind.FINANCING)
    }

    @Test
    fun `the same funding replayed after a restart is not booked again`() {
        disk.saveFunding("s1", PersistedFunding(clock.now(), emptyMap()))
        clock.time = 1_682_560_000_000L
        session()

        clock.time += 3_600_000L
        val afterRestart = session()

        assertThat(afterRestart).isEmpty()
    }
}
