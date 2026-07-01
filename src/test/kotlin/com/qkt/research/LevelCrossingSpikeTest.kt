package com.qkt.research

import com.qkt.common.Money
import java.math.BigDecimal
import kotlin.random.Random
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Spike for the per-bar level-crossing tick-fills design. Two questions, on realistic dense ticks:
 *
 * 1. PARITY: does a prefix running-extreme binary search find the SAME first crossing tick as a
 *    linear replay scan? (the correctness the whole approach rests on)
 * 2. REDUCTION: on fill-possible bars, how many ticks must the engine actually see — the must-feed
 *    set (new-extreme ticks + close) — versus the full intrabar tick count it replays today?
 *
 * Data is a seeded gold-like random walk so the result is reproducible and data-independent: the
 * crossing parity holds for any series, and the reduction is the structural win.
 */
class LevelCrossingSpikeTest {
    private data class Bar(
        val ticks: List<BigDecimal>,
    )

    /** B bars of a gold-like random walk, each with a random dense tick count. */
    private fun synthBars(
        rng: Random,
        bars: Int,
    ): List<Bar> {
        var px = 2700.0
        return List(bars) {
            val n = 30 + rng.nextInt(300) // 30..329 ticks/bar — liquid-gold-ish density
            Bar(
                List(n) {
                    px += (rng.nextDouble() - 0.5) * 0.08 // ~+/- 4 cent steps
                    Money.of(((px * 1e8).toLong())) // scale-8 BigDecimal, like the real Tick
                },
            )
        }
    }

    /** First index whose price >= level (a BUY stop), by scanning every tick. The replay cost. */
    private fun firstCrossingByScan(
        ticks: List<BigDecimal>,
        level: BigDecimal,
    ): Int {
        for (i in ticks.indices) if (ticks[i] >= level) return i
        return -1
    }

    /** First index whose prefix running-max >= level, by binary search over the monotone prefix. */
    private fun firstCrossingBySearch(
        runningMax: List<BigDecimal>,
        level: BigDecimal,
    ): Int {
        var lo = 0
        var hi = runningMax.size // lower-bound: first index with runningMax[i] >= level
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (runningMax[mid] >= level) hi = mid else lo = mid + 1
        }
        return if (lo < runningMax.size) lo else -1
    }

    private fun runningMax(ticks: List<BigDecimal>): List<BigDecimal> {
        val out = ArrayList<BigDecimal>(ticks.size)
        var m = ticks[0]
        for (t in ticks) {
            if (t > m) m = t
            out.add(m)
        }
        return out
    }

    @Test
    fun `prefix-extreme search finds the same first crossing as a linear scan`() {
        val rng = Random(42)
        val bars = synthBars(rng, 3000)
        var checked = 0
        var mismatches = 0
        var totalTicks = 0L
        var totalMustFeed = 0L
        var fillBars = 0

        // measure both directions over all fill-possible bars
        val tScan = System.nanoTime()
        var scanSink = 0
        for (bar in bars) {
            val lo = bar.ticks.min()
            val hi = bar.ticks.max()
            // a level a random fraction into the bar's range -> a fill-possible bar that crosses
            val level = lo.add(hi.subtract(lo).multiply(BigDecimal("0.6")))
            scanSink += firstCrossingByScan(bar.ticks, level)
        }
        val scanNanos = System.nanoTime() - tScan

        val tSearch = System.nanoTime()
        var searchSink = 0
        for (bar in bars) {
            val rmax = runningMax(bar.ticks)
            val lo = bar.ticks.min()
            val hi = bar.ticks.max()
            val level = lo.add(hi.subtract(lo).multiply(BigDecimal("0.6")))
            searchSink += firstCrossingBySearch(rmax, level)
        }
        val searchNanos = System.nanoTime() - tSearch

        // parity + reduction accounting
        for (bar in bars) {
            val rmax = runningMax(bar.ticks)
            val lo = bar.ticks.min()
            val hi = bar.ticks.max()
            val level = lo.add(hi.subtract(lo).multiply(BigDecimal("0.6")))
            val byScan = firstCrossingByScan(bar.ticks, level)
            val bySearch = firstCrossingBySearch(rmax, level)
            checked++
            if (byScan != bySearch) mismatches++
            if (byScan >= 0) {
                fillBars++
                totalTicks += bar.ticks.size
                // must-feed (position-open case): every new running-max + new running-min tick, + close
                var rec = 0
                var mx = bar.ticks[0]
                var mn = bar.ticks[0]
                for (t in bar.ticks) {
                    if (t > mx) {
                        mx = t
                        rec++
                    }
                    if (t < mn) {
                        mn = t
                        rec++
                    }
                }
                totalMustFeed += (rec + 1).toLong() // + close tick
            }
        }

        assertThat(mismatches).describedAs("crossing mismatches over $checked bars").isZero()

        val ratio = totalTicks.toDouble() / totalMustFeed
        println("=== LEVEL-CROSSING SPIKE (bars=$checked, fill-bars=$fillBars) ===")
        println("PARITY: search==scan on every bar (mismatches=$mismatches)")
        println("avg ticks/fill-bar     : ${totalTicks / fillBars}")
        println("avg must-feed/fill-bar : ${totalMustFeed / fillBars}  (new-extreme ticks + close)")
        println("tick reduction         : ${"%.1f".format(ratio)}x fewer ticks fed to the engine on fill bars")
        println(
            "find-crossing time     : scan=${scanNanos / 1_000_000}ms search=${searchNanos / 1_000_000}ms (sinks $scanSink/$searchSink)",
        )
    }
}
