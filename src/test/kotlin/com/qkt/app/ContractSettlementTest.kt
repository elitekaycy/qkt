package com.qkt.app

import com.qkt.accounting.CostKind
import com.qkt.accounting.MoneyAmount
import com.qkt.accounting.VenueCost
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.execution.ExitReason
import com.qkt.execution.LegIntent
import com.qkt.positions.LegRole
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Several strategies on one account: a contract-level settlement closes each one's own holding. */
class ContractSettlementTest {
    private val clock = FixedClock(9L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val positions = StrategyPositionTracker()
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val x = "DERIBIT:BTC_USDC_9OCT26_81000_P"

    init {
        ContractSettlement(bus, positions).bind(listOf("a", "b", "c"))
        bus.subscribe<BrokerEvent.OrderFilled> { fills += it }
    }

    private fun hold(
        strategy: String,
        side: Side,
        quantity: String,
    ) = positions.applyFill(
        BrokerEvent.OrderFilled("o-$strategy", null, x, side, BigDecimal("600"), BigDecimal(quantity), strategy),
        LegIntent.Open("leg-$strategy", LegRole.PRIMARY),
    )

    @Test
    fun `each holder settles its own side at the price, sharing the venue's costs by holding`() {
        hold("a", Side.BUY, "0.2")
        hold("b", Side.SELL, "0.1")

        bus.publish(
            ContractSettled(
                x,
                BigDecimal("1000"),
                listOf(VenueCost(CostKind.EXCHANGE_FEE, MoneyAmount(BigDecimal("3"), "USDC"), 9L)),
            ),
        )

        assertThat(fills.map { listOf(it.strategyId, it.side, it.quantity.toPlainString(), it.price.toPlainString()) })
            .containsExactly(listOf("a", Side.SELL, "0.2", "1000"), listOf("b", Side.BUY, "0.1", "1000"))
        assertThat(fills).allMatch { it.exitReason == ExitReason.EXPIRY && !it.updatesOrderExecution }
        assertThat(
            fills.map {
                it.typedVenueCosts
                    .single()
                    .amount.amount
            },
        ).containsExactly(BigDecimal("2"), BigDecimal("1"))
    }

    @Test
    fun `holders that net to nothing at the venue still each settle, and a flat strategy is left alone`() {
        hold("a", Side.BUY, "0.1")
        hold("b", Side.SELL, "0.1")

        bus.publish(ContractSettled(x, BigDecimal("1000")))

        assertThat(fills.map { it.strategyId }).containsExactly("a", "b")
    }
}
