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

    @Test
    fun `updateStrategies refreshes per-strategy peak between fills`() {
        val positions = PositionTracker()
        val prices = MarketPriceTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPositions = StrategyPositionTracker()
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val tracker = EquityTracker(pnl, strategyPnL)

        strategyPositions.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("80500"))
        tracker.updateStrategy("A")
        assertThat(tracker.peakEquityFor("A")).isEqualByComparingTo(BigDecimal("500"))

        // Price climbs further with no new fill — a tick refresh must lift the per-strategy peak.
        prices.update("BTCUSDT", Money.of("82000"))
        tracker.updateStrategies()
        assertThat(tracker.peakEquityFor("A")).isEqualByComparingTo(BigDecimal("2000"))
    }

    @Test
    fun `trailing drawdown measures giveback against peak equity, not peak profit`() {
        // $10k account, $200 peak profit, $100 giveback. On a 0-based PnL series this
        // read as a 50% drawdown; against true equity it is under 1%.
        val pnl = FakePnL(BigDecimal("200"), Money.ZERO)
        val tracker =
            EquityTracker(
                pnl,
                StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
                BigDecimal("10000"),
            )
        tracker.update() // equity 10200, peak 10200
        pnl.realized = BigDecimal("100")
        tracker.update() // equity 10100

        val dd = DrawdownTracker(tracker).globalDrawdown()
        val expected =
            BigDecimal("100")
                .divide(BigDecimal("10200"), Money.CONTEXT)
                .setScale(Money.SCALE, Money.ROUNDING)
        assertThat(dd).isEqualByComparingTo(expected)
        assertThat(dd).isLessThan(BigDecimal("0.02"))
    }

    @Test
    fun `peak starts at the starting balance before any profit exists`() {
        val pnl = FakePnL(Money.ZERO, Money.ZERO)
        val tracker =
            EquityTracker(
                pnl,
                StrategyPnL(StrategyPositionTracker(), MarketPriceTracker()),
                BigDecimal("10000"),
            )
        tracker.update()
        assertThat(tracker.peakEquity()).isEqualByComparingTo(BigDecimal("10000"))
        // A small immediate loss reads as a small fraction of equity, not a degenerate ratio.
        pnl.realized = BigDecimal("-100")
        tracker.update()
        assertThat(DrawdownTracker(tracker).globalDrawdown()).isEqualByComparingTo(BigDecimal("0.01"))
    }
}
