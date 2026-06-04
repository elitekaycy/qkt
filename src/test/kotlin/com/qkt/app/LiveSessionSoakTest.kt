package com.qkt.app

import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.TradingCalendar
import com.qkt.dsl.compile.AstCompiler
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.InMemoryMarketSource
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test

/**
 * #65 — soak coverage for the live engine: the slow resource growth that only a long
 * uptime surfaces, which unit and stress tests miss.
 *
 * Two angles (see `docs/superpowers/specs/2026-06-04-issue65-soak-design.md`):
 *  1. session churn — start/stop many sessions, prove no thread/executor leak as the
 *     daemon cycles strategies over its uptime.
 *  2. sustained ingest — one non-trading session ingests millions of ticks, prove the
 *     candle ring stays bounded and retained heap plateaus.
 *
 * Tagged `soak` so it stays out of default CI (excluded in `build.gradle.kts`). Run via:
 *
 *   ./gradlew test -PincludeTags=soak --tests 'com.qkt.app.LiveSessionSoakTest'
 *
 * Scale for a real multi-hour soak with `-Dsoak.ticks=` / `-Dsoak.cycles=`; the defaults
 * keep each test to tens of seconds.
 */
@Tag("soak")
class LiveSessionSoakTest {
    private val now = Instant.parse("2024-01-15T15:00:00Z")
    private val symbol = "BACKTEST:BTCUSDT"
    private val cycles = System.getProperty("soak.cycles")?.toInt() ?: 200
    private val total = System.getProperty("soak.ticks")?.toLong() ?: 3_000_000L

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

    @Test
    fun `a long-lived session holds bounded candle history and steady heap`() {
        val hub = CandleHub()
        val strategy =
            AstCompiler().compile((Dsl.parse(NON_TRADING_STRATEGY) as ParseResult.Success).value)
        val key = (strategy as DslCompiledStrategy).declaredStreams.values.first()

        val src = GeneratingLiveSource(symbol, total, baseTs = now.toEpochMilli())
        // Warmup bars (used only if the strategy declares a warmup); also registers symbol support.
        src.seedBars(symbol, TimeWindow.ONE_MINUTE, warmupCandles())

        val handle =
            LiveSession(
                strategies = listOf("soak" to strategy),
                source = src,
                symbols = listOf(symbol),
                candleWindow = TimeWindow.ONE_MINUTE,
                candleHub = hub,
                clock = FixedClock(time = now.toEpochMilli()),
                calendar = TradingCalendar.crypto(),
            ).start()

        // Retained heap right after warmup (rings filled, JIT warm) is the leak-free baseline.
        // We compare it against retained heap once the whole run quiesces — a per-tick leak
        // shows up as the end sitting far above the start. Sampling mid-run instead would catch
        // uncollected garbage, not retained memory, and flap.
        val ringSamples = ArrayList<Int>()
        var baselineHeap = 0L
        for (q in 1..CHECKPOINTS) {
            val threshold = total * q / CHECKPOINTS
            awaitUntil(timeoutMs = 120_000) { src.produced() >= threshold || !handle.running }
            ringSamples.add(hub.historySize(key))
            if (q == 1) baselineHeap = retainedHeapBytes()
        }
        assertThat(handle.awaitTermination(Duration.ofSeconds(120))).isTrue()
        val finalHeap = retainedHeapBytes()

        println(
            "LiveSessionSoak: $total ticks, ring sizes=$ringSamples, " +
                "baseline=${baselineHeap / 1_048_576}MB final=${finalHeap / 1_048_576}MB",
        )

        // Candle ring is bounded: never grows with tick count, stays under a sane ceiling.
        assertThat(ringSamples).allSatisfy { assertThat(it).isLessThanOrEqualTo(RING_CEILING) }
        assertThat(ringSamples.last()).isLessThanOrEqualTo(ringSamples.first())
        // Heap plateaus: retained memory after the full run is not materially above the
        // post-warmup baseline. Generous ceiling (2x or +32MB) so GC jitter never trips it,
        // while a real per-tick leak over millions of ticks (hundreds of MB) still does.
        val ceiling = maxOf(baselineHeap * 2, baselineHeap + 32L * 1_048_576)
        assertThat(finalHeap)
            .withFailMessage(
                "retained heap grew from ${baselineHeap / 1_048_576}MB to ${finalHeap / 1_048_576}MB " +
                    "over $total ticks — a per-tick leak",
            ).isLessThanOrEqualTo(ceiling)
    }

    /** Used heap after pushing the collector hard, so the reading approximates retained memory. */
    private fun retainedHeapBytes(): Long {
        System.gc()
        Thread.sleep(50)
        System.runFinalization()
        System.gc()
        Thread.sleep(50)
        val rt = Runtime.getRuntime()
        return rt.totalMemory() - rt.freeMemory()
    }

    private fun warmupCandles(): List<Candle> {
        val base = now.toEpochMilli()
        return (60 downTo 1).map { i ->
            val start = base - i * 60_000L
            Candle(symbol, m("50000"), m("50000"), m("50000"), m("50000"), m("1"), start, start + 60_000L)
        }
    }

    private fun m(v: String) = Money.of(v)

    /**
     * Generates `total` deterministic random-walk ticks on demand and retains none, so the
     * feed itself never masquerades as heap growth. `produced` lets the test checkpoint progress.
     */
    private class GeneratingLiveSource(
        private val symbol: String,
        private val total: Long,
        private val baseTs: Long,
    ) : InMemoryMarketSource() {
        private val producedCount = AtomicLong(0)

        fun produced(): Long = producedCount.get()

        override fun liveTicks(symbols: List<String>): TickFeed =
            object : TickFeed {
                private var i = 0L

                // Walk in Double, not BigDecimal: a BigDecimal walk grows its scale by a few
                // digits every tick (multiply accumulates precision), so by a few hundred
                // thousand ticks each tick's math is glacial. Double is O(1) and plenty for a
                // synthetic price.
                private var price = 50_000.0
                private val random = java.util.Random(0x5A0AL)
                private val tickInterval = 60_000L / 10L

                override fun next(): Tick? {
                    if (i >= total) return null
                    val deltaBps = random.nextInt(11) - 5
                    price += price * deltaBps / 10_000.0
                    if (price <= 0) price = 50_000.0
                    val tick =
                        Tick(
                            symbol,
                            Money.of(String.format(java.util.Locale.ROOT, "%.2f", price)),
                            baseTs + i * tickInterval,
                        )
                    i++
                    producedCount.incrementAndGet()
                    return tick
                }
            }
    }

    private inline fun awaitUntil(
        timeoutMs: Long,
        condition: () -> Boolean,
    ): Boolean {
        val deadlineNs = System.nanoTime() + timeoutMs * 1_000_000
        while (System.nanoTime() < deadlineNs) {
            if (condition()) return true
            Thread.sleep(2)
        }
        return condition()
    }

    private companion object {
        const val SLACK = 2
        const val CHECKPOINTS = 4
        const val RING_CEILING = 10_000

        val NON_TRADING_STRATEGY =
            """
            STRATEGY soak VERSION 1
            SYMBOLS
              x = BACKTEST:BTCUSDT EVERY 1m
            RULES
              WHEN x.close < 0 THEN BUY x SIZING 0.01
            """.trimIndent()
    }
}
