package com.qkt.app

import com.qkt.app.LiveSessionSoakFixtures.awaitUntil
import com.qkt.app.LiveSessionSoakFixtures.now
import com.qkt.app.LiveSessionSoakFixtures.symbol
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/** Session churn soak: start/stop many sessions and prove no thread/executor leak. See [LiveSessionSoakFixtures]. */
@Tag("soak")
class LiveSessionSoakSessionChurnTest {
    private val cycles = System.getProperty("soak.cycles")?.toInt() ?: 200

    private fun noopStrategy(): Strategy =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {}
        }

    /** Live threads a [LiveSession] spawns; both must die on `stop()`. */
    private fun liveSessionThreadCount(): Int =
        Thread.getAllStackTraces().keys.count {
            it.isAlive &&
                (it.name.startsWith("qkt-live-engine") || it.name.startsWith("qkt-schedule-heartbeat"))
        }

    @Test
    fun `cycling many sessions does not leak engine threads or executors`() {
        val baseline = liveSessionThreadCount()

        repeat(cycles) {
            val src = InMemoryMarketSource()
            src.seedLive(symbol, listOf(Tick(symbol, Money.of("100"), now.toEpochMilli())))
            val handle =
                LiveSession(
                    strategies = listOf("soak" to noopStrategy()),
                    source = src,
                    symbols = listOf(symbol),
                    candleWindow = TimeWindow.ONE_MINUTE,
                    clock = FixedClock(time = now.toEpochMilli()),
                    calendar = TradingCalendar.crypto(),
                ).start()
            // Feed drains after the single tick, so the engine thread exits on its own;
            // stop() is what must tear down the schedule-heartbeat executor.
            handle.awaitTermination(Duration.ofSeconds(5))
            handle.stop()
        }

        // Threads die asynchronously; give them a bounded moment to settle.
        val settled =
            awaitUntil(timeoutMs = 15_000) { liveSessionThreadCount() <= baseline + SLACK }
        val finalCount = liveSessionThreadCount()
        assertThat(settled)
            .withFailMessage(
                "after $cycles start/stop cycles, $finalCount live session threads remain " +
                    "(baseline $baseline) — a stop() teardown leak",
            ).isTrue()
    }

    private companion object {
        const val SLACK = 2
    }
}
