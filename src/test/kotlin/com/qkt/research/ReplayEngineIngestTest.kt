package com.qkt.research

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `ingest` is the per-tick step the fan-out sweep driver calls: it must apply a tick exactly as the
 * internal feed loop does, so a caller can push a shared decoded feed into many engines.
 */
class ReplayEngineIngestTest {
    private val noopStrategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    @Test
    fun `ingest advances the engine like the feed loop`() {
        val engine =
            ReplayEngine(
                strategies = listOf("s" to noopStrategy),
                feed = SequenceTickFeed(emptySequence()),
                candleWindow = TimeWindow.ONE_MINUTE,
            )
        (1..5).forEach { i -> engine.ingest(Tick("X", Money.of((100 + i).toString()), i * 60_000L)) }
        assertThat(engine.ticksIngested).isEqualTo(5L)
        assertThat(engine.snapshot().global.tradeCount).isEqualTo(0)
    }
}
