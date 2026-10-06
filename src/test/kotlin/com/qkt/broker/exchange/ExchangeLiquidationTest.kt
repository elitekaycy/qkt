package com.qkt.broker.exchange

import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ExchangeLiquidationTest {
    private val f = ExchangeFixture(slippageTicks = 2, takerFeeRate = "0.0005")

    private fun fills() = f.only<BrokerEvent.OrderFilled>()

    @Test
    fun `a liquidation closes the position at the bid without slippage, charging the taker fee`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("long", Side.BUY, "0.01"))
        f.prices.update(
            Tick(f.sep, BigDecimal("55000.0"), f.clock.time, bid = BigDecimal("54999.0"), ask = BigDecimal("55001.0")),
        )

        f.sim.liquidate(f.sep)

        val close = fills().last()
        assertThat(close.exitReason).isEqualTo(ExitReason.LIQUIDATION)
        assertThat(close.updatesOrderExecution).isFalse()
        assertThat(close.side).isEqualTo(Side.SELL)
        assertThat(close.quantity).isEqualByComparingTo("0.01")
        assertThat(close.price).isEqualByComparingTo("54999.0")
        assertThat(close.strategyId).isEqualTo("s")
        // 0.05% of 0.01 x 54999.
        assertThat(
            close.typedVenueCosts
                .single()
                .amount.amount,
        ).isEqualByComparingTo("0.274995")
    }

    @Test
    fun `working orders on the contract are cancelled before the close`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("long", Side.BUY, "0.01"))
        f.sim.submit(f.stop("protect", Side.SELL, "60000.0"))

        f.sim.liquidate(f.sep)

        val cancel = f.only<BrokerEvent.OrderCancelled>().single()
        assertThat(cancel.clientOrderId).isEqualTo("protect")
        assertThat(cancel.reason).contains("liquidated")
        assertThat(f.events.indexOf(cancel)).isLessThan(f.events.indexOf(fills().last()))
    }

    @Test
    fun `each strategy's short is bought back and a flat strategy gets nothing`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("a", Side.SELL, "0.02"))
        f.sim.submit(f.market("b", Side.BUY, "0.01").copy(strategyId = "flat"))
        f.sim.submit(f.market("c", Side.SELL, "0.01").copy(strategyId = "flat"))

        f.sim.liquidate(f.sep)

        val closes = fills().filter { it.exitReason == ExitReason.LIQUIDATION }
        assertThat(closes.map { Triple(it.strategyId, it.side, it.quantity.toPlainString()) })
            .containsExactly(Triple("s", Side.BUY, "0.020"))
    }

    @Test
    fun `a liquidated position is not settled again at expiry`() {
        f.tick(f.sep, "63000.0")
        f.sim.submit(f.market("long", Side.BUY, "0.01"))
        f.sim.liquidate(f.sep)

        f.tick(f.sep, "63000.5", atMs = f.sepExpiry)

        assertThat(fills().none { it.exitReason == ExitReason.EXPIRY }).isTrue()
        assertThat(f.settlements.entries).isEmpty()
    }
}
