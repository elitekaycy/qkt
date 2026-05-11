package com.qkt.positions

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyPositionTrackerTest {
    private val seq = AtomicLong()

    private fun fill(
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
        timestamp: Long = 0L,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-${seq.incrementAndGet()}",
        brokerOrderId = "b",
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = timestamp,
    )

    @Test
    fun `same-symbol fills from different strategies do not commingle`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.SELL, "0.3", "81000"))

        assertThat(tracker.positionFor("A", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("1"))
        assertThat(tracker.positionFor("B", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("-0.3"))
    }

    @Test
    fun `realized accrues per strategy independently`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        val realizedA = tracker.applyFill(fill("A", "BTCUSDT", Side.SELL, "1", "82000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "79000"))

        assertThat(realizedA).isEqualByComparingTo(BigDecimal("2000"))
        assertThat(tracker.positionFor("A", "BTCUSDT")).isNull()
        assertThat(tracker.positionFor("B", "BTCUSDT")?.quantity).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `positionsFor returns only that strategy's positions`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("A", "ETHUSDT", Side.BUY, "10", "3000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "79000"))

        assertThat(tracker.positionsFor("A").keys).containsExactlyInAnyOrder("BTCUSDT", "ETHUSDT")
        assertThat(tracker.positionsFor("B").keys).containsExactlyInAnyOrder("BTCUSDT")
    }

    @Test
    fun `applyFill with blank strategyId is a noop`() {
        val tracker = StrategyPositionTracker()
        val realized = tracker.applyFill(fill("", "BTCUSDT", Side.BUY, "1", "80000"))

        assertThat(realized).isEqualByComparingTo(Money.ZERO)
        assertThat(tracker.allByStrategy()).isEmpty()
    }

    @Test
    fun `driftFor returns difference between strategy sum and broker view`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTCUSDT", Side.BUY, "1", "80000"))
        tracker.applyFill(fill("B", "BTCUSDT", Side.BUY, "0.5", "81000"))

        val brokerView =
            object : PositionProvider {
                override fun positionFor(symbol: String): Position? =
                    if (symbol == "BTCUSDT") Position("BTCUSDT", BigDecimal("1.0"), BigDecimal("80000")) else null

                override fun allPositions(): Map<String, Position> =
                    positionFor("BTCUSDT")?.let { mapOf("BTCUSDT" to it) } ?: emptyMap()
            }

        val drift = tracker.driftFor("BTCUSDT", brokerView)
        assertThat(drift).isEqualByComparingTo(BigDecimal("0.5"))
    }

    @Test
    fun `openedAt is set on flat-to-long transition`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "100", timestamp = 1000L))
        val pos = tracker.positionFor("A", "BTC")!!
        assertThat(pos.openedAt).isEqualTo(1000L)
    }

    @Test
    fun `openedAt is preserved on add-to-position`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "100", timestamp = 1000L))
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "110", timestamp = 2000L))
        val pos = tracker.positionFor("A", "BTC")!!
        assertThat(pos.openedAt).isEqualTo(1000L)
    }

    @Test
    fun `position fully closed clears openedAt`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "100", timestamp = 1000L))
        tracker.applyFill(fill("A", "BTC", Side.SELL, "1", "110", timestamp = 2000L))
        assertThat(tracker.positionFor("A", "BTC")).isNull()
    }

    @Test
    fun `reopening after close sets new openedAt`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "100", timestamp = 1000L))
        tracker.applyFill(fill("A", "BTC", Side.SELL, "1", "110", timestamp = 2000L))
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "120", timestamp = 3000L))
        val pos = tracker.positionFor("A", "BTC")!!
        assertThat(pos.openedAt).isEqualTo(3000L)
    }

    @Test
    fun `long-to-short flip resets openedAt to flip timestamp`() {
        val tracker = StrategyPositionTracker()
        tracker.applyFill(fill("A", "BTC", Side.BUY, "1", "100", timestamp = 1000L))
        tracker.applyFill(fill("A", "BTC", Side.SELL, "3", "90", timestamp = 2000L))
        val pos = tracker.positionFor("A", "BTC")!!
        assertThat(pos.quantity).isEqualByComparingTo(BigDecimal("-2"))
        assertThat(pos.openedAt).isEqualTo(2000L)
    }
}
