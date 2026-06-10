package com.qkt.pnl

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPnLTest {
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

    private fun fillWithId(
        clientOrderId: String,
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = clientOrderId,
        brokerOrderId = "b-${seq.incrementAndGet()}",
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = 0L,
    )

    @Test
    fun `hedged net-flat legs report the locked spread as unrealized`() {
        // A straddle that filled both ways: long 0.1 @ 2000 and short 0.1 @ 1997. The
        // netted view is quantity zero, but both legs are open at the broker with a
        // locked-in spread loss of (1997 - 2000) x 0.1 = -0.3. Equity and halts must
        // see that loss at ANY mark, not zero.
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        tracker.registerIndependentOpen("A", "c-long", "leg-long")
        tracker.registerIndependentOpen("A", "c-short", "leg-short")
        tracker.applyFill(fillWithId("c-long", "A", "XAUUSD", Side.BUY, "0.1", "2000"))
        tracker.applyFill(fillWithId("c-short", "A", "XAUUSD", Side.SELL, "0.1", "1997"))

        prices.update("XAUUSD", Money.of("2010"))
        assertThat(pnl.unrealizedFor("A", "XAUUSD")).isEqualByComparingTo("-0.3")

        // The locked loss is mark-independent — that's what "locked in" means.
        prices.update("XAUUSD", Money.of("1980"))
        assertThat(pnl.unrealizedFor("A", "XAUUSD")).isEqualByComparingTo("-0.3")

        // And it flows into the totals the halt rules consume.
        assertThat(pnl.unrealizedTotalFor("A")).isEqualByComparingTo("-0.3")
        assertThat(pnl.totalFor("A")).isEqualByComparingTo("-0.3")
    }

    @Test
    fun `realizedFor accrues only this strategy's closes`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        val rA1 = tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        val rA2 = tracker.applyFill(fill("A", "BTCUSDT", Side.SELL, "1", "82000"))
        pnl.recordRealized("A", rA1)
        pnl.recordRealized("A", rA2)

        val rB = tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "80000"))
        pnl.recordRealized("B", rB)

        assertThat(pnl.realizedFor("A")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(pnl.realizedFor("B")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `unrealizedFor uses this strategy's avg entry`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        prices.update("BTCUSDT", Money.of("82000"))

        assertThat(pnl.unrealizedFor("A", "BTCUSDT")).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(pnl.unrealizedFor("B", "BTCUSDT")).isEqualByComparingTo(Money.ZERO)
    }

    @Test
    fun `totalFor sums realized and unrealized for the strategy only`() {
        val tracker = StrategyPositionTracker()
        val prices = MarketPriceTracker()
        val pnl = StrategyPnL(tracker, prices)

        val rA = tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        pnl.recordRealized("A", rA)
        prices.update("BTCUSDT", Money.of("82000"))

        assertThat(pnl.totalFor("A")).isEqualByComparingTo(BigDecimal("2000"))
    }
}
