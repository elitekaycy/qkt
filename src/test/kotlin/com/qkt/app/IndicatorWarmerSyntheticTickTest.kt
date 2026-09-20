package com.qkt.app

import com.qkt.app.IndicatorWarmerFixtures.candle
import com.qkt.app.IndicatorWarmerFixtures.newPipeline
import com.qkt.app.IndicatorWarmerFixtures.now
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerSyntheticTickTest {
    @Test
    fun `Bars warmup pushes four OHLC ticks per bar through ingestForWarmup`() {
        val source = InMemoryMarketSource()
        val warmupStart = Instant.parse("2024-01-15T14:30:00Z").toEpochMilli()
        val candles =
            (0 until 30).map { i -> candle((100 + i).toString(), warmupStart + i * 60_000L) }
        source.seedBars("X", TimeWindow.ONE_MINUTE, candles)

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 30), now)

        // Four synthetic ticks (O, L, H, C) per bar.
        assertThat(captured).hasSize(120)
        assertThat(captured.map { it.symbol }).allMatch { it == "X" }
        assertThat(captured.first().price).isEqualByComparingTo(Money.of("100"))
        assertThat(captured.last().price).isEqualByComparingTo(Money.of("129"))
    }

    @Test
    fun `synthetic tick timestamp is bar endTime minus one`() {
        val source = InMemoryMarketSource()
        val barStart = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars("X", TimeWindow.ONE_MINUTE, listOf(candle("100", barStart)))

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1), now)

        // Four OHLC ticks; the close tick sits last, at endTime - 1.
        assertThat(captured).hasSize(4)
        assertThat(captured.last().timestamp).isEqualTo(barStart + 60_000L - 1)
    }

    @Test
    fun `warmup ticks carry the bar high and low, not just the close`() {
        val source = InMemoryMarketSource()
        val barStart = Instant.parse("2024-01-15T14:59:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(
                Candle(
                    "X",
                    Money.of("100"),
                    Money.of("110"),
                    Money.of("90"),
                    Money.of("105"),
                    Money.of("1"),
                    barStart,
                    barStart + 60_000L,
                ),
            ),
        )

        val captured = mutableListOf<Tick>()
        val pipeline = newPipeline(strategies = emptyList())
        pipeline.bus.subscribe<WarmupTickEvent> { e -> captured.add(e.tick) }

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 1), now)

        val prices = captured.map { it.price }
        assertThat(prices).anyMatch { it.compareTo(Money.of("110")) == 0 }
        assertThat(prices).anyMatch { it.compareTo(Money.of("90")) == 0 }
    }
}
