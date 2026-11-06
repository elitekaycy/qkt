package com.qkt.backtest.sweep

import com.qkt.common.Money
import com.qkt.marketdata.HistoricalTickFeed
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.SequenceTickFeed
import com.qkt.research.ReplayEngine
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SweepReplayFailureTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    private fun sweep(
        configs: List<Pair<String, Int>>,
        parallelism: Int,
    ) = SweepReplay(
        configs = configs,
        sharedFeed = { HistoricalTickFeed(listOf(Tick("X", Money.of("100"), 60_000L))) },
        engineFor = { label, _ ->
            check(label != "bad") { "engine for $label failed" }
            ReplayEngine(strategies = listOf("s" to idle), feed = SequenceTickFeed(emptySequence()))
        },
        parallelism = parallelism,
    )

    @Test
    fun `duplicate config labels are refused`() {
        assertThatThrownBy { sweep(listOf("a" to 1, "a" to 2), 1) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("config labels must be unique: [a, a]")
    }

    @Test
    fun `a failing worker stops the parallel sweep with its own error`() {
        assertThatThrownBy { sweep(listOf("ok" to 1, "bad" to 2), 2).run() }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("engine for bad failed")
    }
}
