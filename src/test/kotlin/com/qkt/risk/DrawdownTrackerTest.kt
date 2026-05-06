package com.qkt.risk

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DrawdownTrackerTest {
    private val seq = AtomicLong()

    private fun fill(
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-${seq.incrementAndGet()}",
        brokerOrderId = "b",
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = 0L,
    )

    @Test
    fun `drawdown is zero when no positive peak yet`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        equity.update()

        assertThat(drawdown.globalDrawdown()).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `globalDrawdown is fractional peak-to-current`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        equity.update()
        prices.update("BTCUSDT", Money.of("80000"))
        equity.update()

        assertThat(drawdown.globalDrawdown()).isEqualByComparingTo(BigDecimal("1.0"))
    }

    @Test
    fun `per-strategy drawdown is independent`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val equity = EquityTracker(pnl, strategyPnL)
        val drawdown = DrawdownTracker(equity)

        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("B", "ETHUSDT", Side.BUY, "10", "3000"))
        prices.update("BTCUSDT", Money.of("82000"))
        prices.update("ETHUSDT", Money.of("3300"))
        equity.updateStrategy("A")
        equity.updateStrategy("B")

        prices.update("BTCUSDT", Money.of("81000"))
        equity.updateStrategy("A")

        assertThat(drawdown.strategyDrawdown("A")).isEqualByComparingTo(BigDecimal("0.5"))
        assertThat(drawdown.strategyDrawdown("B")).isEqualByComparingTo(Money.ZERO)
    }
}
