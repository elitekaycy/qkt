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

class EquityTrackerTest {
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
    fun `currentEquity tracks realized plus unrealized`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        tracker.update()

        assertThat(tracker.currentEquity()).isEqualByComparingTo(BigDecimal("2000"))
    }

    @Test
    fun `peakEquity is monotonically non-decreasing`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(StrategyPositionTracker(), prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        positions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))
        tracker.update()

        prices.update("BTCUSDT", Money.of("78000"))
        tracker.update()

        assertThat(tracker.peakEquity()).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.currentEquity()).isEqualByComparingTo(BigDecimal("-2000"))
    }

    @Test
    fun `per-strategy equity is tracked independently`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        strategyPositions.applyFill(fill("B", "ETHUSDT", Side.BUY, "10", "3000"))
        prices.update("BTCUSDT", Money.of("82000"))
        prices.update("ETHUSDT", Money.of("2900"))
        tracker.updateStrategy("A")
        tracker.updateStrategy("B")

        assertThat(tracker.currentEquityFor("A")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.currentEquityFor("B")).isEqualByComparingTo(BigDecimal("-1000"))
        assertThat(tracker.peakEquityFor("A")).isEqualByComparingTo(BigDecimal("2000"))
    }
}
