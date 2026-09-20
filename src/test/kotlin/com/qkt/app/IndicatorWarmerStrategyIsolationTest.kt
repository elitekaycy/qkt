package com.qkt.app

import com.qkt.app.IndicatorWarmerFixtures.candle
import com.qkt.app.IndicatorWarmerFixtures.newPipeline
import com.qkt.app.IndicatorWarmerFixtures.now
import com.qkt.candles.TimeWindow
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.WarmupSpec
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class IndicatorWarmerStrategyIsolationTest {
    @Test
    fun `strategies do not see warmup ticks`() {
        val source = InMemoryMarketSource()
        val twoBarsBack = Instant.parse("2024-01-15T14:58:00Z").toEpochMilli()
        source.seedBars(
            "X",
            TimeWindow.ONE_MINUTE,
            listOf(candle("100", twoBarsBack), candle("101", twoBarsBack + 60_000L)),
        )

        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick)
                }
            }
        val pipeline = newPipeline(listOf("test" to strategy))

        IndicatorWarmer(source, pipeline)
            .warmup(listOf("X"), WarmupSpec.Bars(TimeWindow.ONE_MINUTE, count = 2), now)

        assertThat(seen).isEmpty()
    }
}
