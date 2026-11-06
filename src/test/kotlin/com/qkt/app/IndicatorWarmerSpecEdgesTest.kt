package com.qkt.app

import com.qkt.app.IndicatorWarmerFixtures.newPipeline
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class IndicatorWarmerSpecEdgesTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val nowMs = now.toEpochMilli()

    private fun bar(
        close: String,
        startMs: Long,
        endMs: Long = startMs + 60_000L,
    ) = Candle("X", Money.of(close), Money.of(close), Money.of(close), Money.of(close), Money.of("1"), startMs, endMs)

    private fun minutes(
        count: Int,
        lastStartMs: Long,
    ) = (0 until count).map { i -> bar((100 + i).toString(), lastStartMs - (count - 1 - i) * 60_000L) }

    private fun capture(
        source: InMemoryMarketSource,
        run: (IndicatorWarmer) -> Unit,
    ): List<Tick> {
        val pipeline = newPipeline(strategies = emptyList())
        val captured = mutableListOf<Tick>()
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }
        run(IndicatorWarmer(source, pipeline))
        return captured
    }

    @Test
    fun `a tick duration spec warms from one minute bars on a bar only source`() {
        val source = InMemoryMarketSource()
        source.seedBars("X", TimeWindow.ONE_MINUTE, minutes(5, nowMs - 60_000L))

        val ticks = capture(source) { it.warmup(listOf("X"), WarmupSpec.Ticks(Duration.ofMinutes(3)), now) }

        // Three one-minute bars, four synthetic ticks each.
        assertThat(ticks).hasSize(12)
        assertThat(ticks.last().price).isEqualByComparingTo("104")
    }

    @Test
    fun `a tick duration spec on a tick capable source still falls back to one minute bars`() {
        val source =
            object : InMemoryMarketSource() {
                override val capabilities = super.capabilities + MarketSourceCapability.TICKS
            }
        source.seedBars("X", TimeWindow.ONE_MINUTE, minutes(2, nowMs - 60_000L))

        val ticks = capture(source) { it.warmup(listOf("X"), WarmupSpec.Ticks(Duration.ofMinutes(2)), now) }

        assertThat(ticks.map { it.price }).containsOnly(Money.of("100"), Money.of("101"))
    }

    @Test
    fun `a tick duration shorter than a minute is refused`() {
        assertThatThrownBy {
            capture(InMemoryMarketSource()) { it.warmup(listOf("X"), WarmupSpec.Ticks(Duration.ofSeconds(30)), now) }
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("duration too short to derive bar count")
    }

    @Test
    fun `a bar duration shorter than its window is refused`() {
        assertThatThrownBy {
            capture(InMemoryMarketSource()) {
                it.warmup(listOf("X"), WarmupSpec.Duration(TimeWindow.FIVE_MINUTES, Duration.ofMinutes(2)), now)
            }
        }.hasMessageContaining("Duration too short for window")
    }

    @Test
    fun `a per stream spec whose window differs from the stream key is refused`() {
        val spec = mapOf(WarmupStream("X", TimeWindow.FIVE_MINUTES) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 2))

        assertThatThrownBy { capture(InMemoryMarketSource()) { it.warmup(spec, now) } }
            .hasMessageContaining("warmup window mismatch")
    }

    @Test
    fun `a degenerate bar that would replay a tick at or after now is refused as look ahead`() {
        val source = InMemoryMarketSource()
        // Zero-width bar one millisecond before the window boundary: its synthetic ticks step past now.
        source.seedBars("X", TimeWindow.ONE_MINUTE, listOf(bar("100", nowMs - 1, nowMs - 1)))

        assertThatThrownBy {
            capture(source) { it.warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 1), now) }
        }.hasMessageContaining("look-ahead bias")
    }

    @Test
    fun `the elapsed minutes of the forming higher timeframe bar are replayed and later ticks dropped`() {
        val midBar = Instant.parse("2024-01-15T15:03:00Z")
        val source = InMemoryMarketSource()
        val fiveMinStart = Instant.parse("2024-01-15T14:55:00Z").toEpochMilli()
        source.seedBars("X", TimeWindow.FIVE_MINUTES, listOf(bar("90", fiveMinStart, fiveMinStart + 300_000L)))
        val boundary = midBar.toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            minutes(3, boundary - 60_000L) + bar("777", boundary - 1, boundary - 1),
        )

        val ticks =
            capture(source) { it.warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.FIVE_MINUTES, 1), midBar) }

        assertThat(ticks.map { it.timestamp }).allMatch { it < boundary }
        // The closed 5m bar, then 15:00, 15:01, 15:02 and the in-window tick of the degenerate minute.
        assertThat(ticks.map { it.price.toPlainString() }.distinct()).containsExactly("90", "100", "101", "102", "777")
        assertThat(ticks).hasSize(4 + 12 + 2)
    }
}
