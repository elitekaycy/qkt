package com.qkt.app

import com.qkt.app.IndicatorWarmerFixtures.newPipeline
import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.dsl.compile.CandleHub
import com.qkt.events.WarmupTickEvent
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.PerStreamWarmable
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerStreamWarmupBacktestTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")

    private class Warmable(
        override val perStreamWarmup: Map<WarmupStream, WarmupSpec>,
    ) : Strategy,
        PerStreamWarmable {
        override fun onTick(
            tick: Tick,
            ctx: StrategyContext,
            emit: (Signal) -> Unit,
        ) {}
    }

    private fun bar(
        symbol: String,
        startMs: Long,
    ): Candle {
        val ten = Money.of("10")
        return Candle(symbol, ten, ten, ten, ten, Money.of("1"), startMs, startMs + 60_000L)
    }

    @Test
    fun `a backtest stream whose warmup cannot be served is skipped while the others still warm`() {
        val source = InMemoryMarketSource()
        val lastMinute = now.toEpochMilli() - 60_000L
        val history = listOf(bar("GOOD", lastMinute - 60_000L), bar("GOOD", lastMinute))
        source.seedBars("GOOD", TimeWindow.ONE_MINUTE, history)
        val strategy =
            Warmable(
                mapOf(
                    // The spec's window disagrees with the stream's: warming it fails.
                    WarmupStream("BROKEN", TimeWindow.FIVE_MINUTES) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 2),
                    WarmupStream("GOOD", TimeWindow.ONE_MINUTE) to WarmupSpec.Bars(TimeWindow.ONE_MINUTE, 2),
                ),
            )
        val pipeline = newPipeline(strategies = emptyList())
        val warmed = mutableListOf<Tick>()
        pipeline.bus.subscribe<WarmupTickEvent> { warmed += it.tick }

        PerStreamWarmupCoordinator(listOf("s" to strategy), source, CandleHub(), now, requireFullHistory = false)
            .warm(pipeline)

        assertThat(warmed).hasSize(8)
        assertThat(warmed.map { it.symbol }).containsOnly("GOOD")
    }
}
