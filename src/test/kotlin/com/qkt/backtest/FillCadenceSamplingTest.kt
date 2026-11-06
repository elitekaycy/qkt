package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.book.BookSnapshot
import com.qkt.risk.book.BookStateSource
import com.qkt.risk.book.Exposure
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** Under FILL cadence the book collectors sample on fills only, never on ticks. */
class FillCadenceSamplingTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())

    private fun fill(t: Long) {
        clock.time = t
        bus.publish(BrokerEvent.OrderFilled("o$t", "b$t", "X", Side.BUY, Money.of("100"), Money.of("1"), "a", t))
    }

    private fun tick(t: Long) {
        clock.time = t
        bus.publish(TickEvent(Tick("X", Money.of("100"), t)))
    }

    @Test
    fun `the book risk monitor samples each fill`() {
        val sampled = mutableListOf<Long>()
        val source =
            object : BookStateSource {
                override fun sample(timestampMs: Long): BookSnapshot {
                    sampled += timestampMs
                    return BookSnapshot(
                        timestampMs,
                        BigDecimal("10000"),
                        Exposure(BigDecimal("5"), BigDecimal("5"), emptyMap()),
                        emptyMap(),
                    )
                }
            }
        val monitor =
            BookRiskMonitor(SampleCadence.FILL, bus, source, strategyCount = 2, startingBalance = BigDecimal("10000"))

        tick(1)
        fill(2)
        fill(3)
        fill(4)

        assertThat(sampled).containsExactly(2L, 3L, 4L)
        assertThat(monitor.result(BigDecimal("252"))?.series?.map { it.timestampMs }).containsExactly(2L, 3L, 4L)
    }

    @Test
    fun `the book return collector folds a return per fill`() {
        val prices = MarketPriceTracker()
        val collector =
            BookReturnCollector(
                SampleCadence.FILL,
                bus,
                PnLCalculator(StrategyPositionTracker().account, prices),
                StrategyPnL(StrategyPositionTracker(), prices),
                listOf("a", "b"),
                BigDecimal("10000"),
            )

        repeat(5) { tick(it.toLong()) }
        assertThat(collector.result()).isNull()

        (10L..12L).forEach(::fill)
        assertThat(collector.result()).isNotNull()
    }
}
