package com.qkt.research

import com.qkt.backtest.SampleCadence
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ReplayEngineGuardsTest {
    private val idle =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) = Unit
        }

    private class ClosableFeed : TickFeed {
        var closed = false

        override fun next(): Tick? = null

        override fun close() {
            closed = true
        }
    }

    @Test
    fun `candle-close cadence without a candle window is refused`() {
        assertThatThrownBy {
            ReplayEngine(strategies = listOf("s" to idle), feed = ClosableFeed(), cadence = SampleCadence.CANDLE_CLOSE)
        }.isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires candleWindow")
    }

    @Test
    fun `closing the engine closes its feed`() {
        val feed = ClosableFeed()
        val engine = ReplayEngine(strategies = listOf("s" to idle), feed = feed)

        engine.close()

        assertThat(feed.closed).isTrue()
    }
}
