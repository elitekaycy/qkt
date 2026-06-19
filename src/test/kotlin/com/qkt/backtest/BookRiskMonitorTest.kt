package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.events.TickEvent
import com.qkt.marketdata.Tick
import com.qkt.risk.book.BookSnapshot
import com.qkt.risk.book.BookStateSource
import com.qkt.risk.book.Exposure
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class BookRiskMonitorTest {
    private fun snap(
        ts: Long,
        gross: String,
        net: String,
        equity: String,
    ) = BookSnapshot(
        timestampMs = ts,
        bookEquity = BigDecimal(equity),
        exposure = Exposure(BigDecimal(gross), BigDecimal(net), emptyMap()),
        perStrategyPnl = mapOf("a" to BigDecimal.ZERO, "b" to BigDecimal.ZERO),
    )

    @Test
    fun `monitor records series, peak exposure, and book vol`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val scripted =
            ArrayDeque(
                listOf(
                    snap(1, "100", "100", "10000"),
                    snap(2, "300", "200", "10100"),
                    snap(3, "150", "50", "10050"),
                ),
            )
        val source =
            object : BookStateSource {
                override fun sample(timestampMs: Long) = scripted.removeFirst()
            }
        val monitor =
            BookRiskMonitor(SampleCadence.TICK, bus, source, strategyCount = 2, startingBalance = BigDecimal("10000"))

        for (t in 1L..3L) {
            clock.time = t
            bus.publish(TickEvent(Tick("X", Money.of("100"), t)))
        }

        val r = monitor.result(BigDecimal("252"))!!
        assertThat(r.series).hasSize(3)
        assertThat(r.maxGrossExposure).isEqualByComparingTo("300")
        assertThat(r.maxNetExposure).isEqualByComparingTo("200")
        assertThat(r.bookVol).isNotNull()
    }

    @Test
    fun `single strategy yields null report`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val source =
            object : BookStateSource {
                override fun sample(timestampMs: Long) = snap(1, "1", "1", "10000")
            }
        val monitor =
            BookRiskMonitor(SampleCadence.TICK, bus, source, strategyCount = 1, startingBalance = BigDecimal("10000"))
        clock.time = 1
        bus.publish(TickEvent(Tick("X", Money.of("100"), 1)))
        assertThat(monitor.result(BigDecimal("252"))).isNull()
    }
}
