package com.qkt.app

import com.qkt.app.IndicatorWarmerFixtures.candle
import com.qkt.app.IndicatorWarmerFixtures.newPipeline
import com.qkt.app.IndicatorWarmerFixtures.now
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.WarmupSpec
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerRangeTest {
    private val candleStart = Instant.parse("2024-01-15T14:00:00Z").toEpochMilli()

    @Test
    fun `None spec is a no-op`() {
        val source = InMemoryMarketSource()
        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline).warmup(listOf("X"), WarmupSpec.None, now)

        assertThat(captured).isEmpty()
    }

    @Test
    fun `warmup range upper bound excludes the current incomplete bar`() {
        val source = InMemoryMarketSource()
        val rightBeforeNow = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(
                candle("99", Instant.parse("2024-01-15T14:58:00Z").toEpochMilli()),
                candle("100", rightBeforeNow),
                candle("999", Instant.parse("2024-01-15T15:00:00Z").toEpochMilli()),
            ),
        )

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2), now)

        assertThat(captured.map { it.price }).noneMatch { it.compareTo(Money.of("999")) == 0 }
    }

    @Test
    fun `Duration spec converts duration to bar count`() {
        val source = InMemoryMarketSource()
        val candles =
            (0 until 60).map { i -> candle((100 + i).toString(), candleStart + i * 60_000L) }
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(
                symbols = listOf("X"),
                spec = WarmupSpec.Duration(TimeWindow.ONE_MINUTE, Duration.ofMinutes(15)),
                now = now,
            )

        // 15 bars x 4 OHLC ticks.
        assertThat(captured).hasSize(60)
    }
}
