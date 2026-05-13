package com.qkt.tools.parity

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

data class CapturedTick(
    val source: Source,
    val capturedAtMs: Long,
    val brokerTimeMs: Long?,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val last: BigDecimal?,
) {
    enum class Source { TV, MT5 }

    val mid: BigDecimal?
        get() {
            val b = bid ?: return null
            val a = ask ?: return null
            return b.add(a).divide(BigDecimal(2))
        }
}

data class SourceStats(
    val count: Int,
    val ticksPerSec: BigDecimal,
    val meanGapMs: Long,
    val maxGapMs: Long,
    val p95GapMs: Long,
    val firstAtMs: Long,
    val lastAtMs: Long,
)

data class TicksParityStats(
    val tv: SourceStats,
    val mt5: SourceStats,
    val pairedCount: Int,
    val meanMidDelta: BigDecimal,
    val p95MidDelta: BigDecimal,
    val maxMidDelta: BigDecimal,
    val meanRelativeBps: BigDecimal,
    val tvAheadOfMt5Ms: Long,
    val unpairedTvCount: Int,
    val unpairedMt5Count: Int,
) {
    fun toMarkdown(
        title: String,
        windowSeconds: Long,
        generatedAt: Instant,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Generated: $generatedAt")
            appendLine("Window: ${windowSeconds}s")
            appendLine()
            appendLine("## Throughput")
            appendLine()
            appendLine("| Source | ticks | ticks/s | mean gap (ms) | p95 gap (ms) | max gap (ms) |")
            appendLine("| --- | --- | --- | --- | --- | --- |")
            appendLine(
                "| TV | ${tv.count} | ${tv.ticksPerSec.toPlainString()} | ${tv.meanGapMs} | " +
                    "${tv.p95GapMs} | ${tv.maxGapMs} |",
            )
            appendLine(
                "| MT5 | ${mt5.count} | ${mt5.ticksPerSec.toPlainString()} | ${mt5.meanGapMs} | " +
                    "${mt5.p95GapMs} | ${mt5.maxGapMs} |",
            )
            appendLine()
            appendLine("## Mid-price parity")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("| --- | --- |")
            appendLine("| Paired MT5 ticks | $pairedCount |")
            appendLine("| Unpaired TV ticks | $unpairedTvCount |")
            appendLine("| Unpaired MT5 ticks | $unpairedMt5Count |")
            appendLine("| Mean abs(mid_tv − mid_mt5) | ${meanMidDelta.toPlainString()} |")
            appendLine("| p95 abs(mid_tv − mid_mt5) | ${p95MidDelta.toPlainString()} |")
            appendLine("| Max abs(mid_tv − mid_mt5) | ${maxMidDelta.toPlainString()} |")
            appendLine("| Mean relative (bps) | ${meanRelativeBps.toPlainString()} |")
            appendLine("| Median TV-vs-MT5 capture skew (ms) | $tvAheadOfMt5Ms (>0 ⇒ TV arrives first) |")
        }
}

object TicksParity {
    private val MC = MathContext(12, RoundingMode.HALF_UP)

    fun compare(
        ticks: List<CapturedTick>,
        windowMs: Long,
    ): TicksParityStats {
        val tv = ticks.filter { it.source == CapturedTick.Source.TV }.sortedBy { it.capturedAtMs }
        val mt5 = ticks.filter { it.source == CapturedTick.Source.MT5 }.sortedBy { it.capturedAtMs }

        val tvStats = stats(tv, windowMs)
        val mt5Stats = stats(mt5, windowMs)

        val tvWithMid = tv.filter { it.mid != null }
        val mt5WithMid = mt5.filter { it.mid != null }

        if (tvWithMid.isEmpty() || mt5WithMid.isEmpty()) {
            return TicksParityStats(
                tv = tvStats,
                mt5 = mt5Stats,
                pairedCount = 0,
                meanMidDelta = BigDecimal.ZERO,
                p95MidDelta = BigDecimal.ZERO,
                maxMidDelta = BigDecimal.ZERO,
                meanRelativeBps = BigDecimal.ZERO,
                tvAheadOfMt5Ms = 0L,
                unpairedTvCount = tv.size,
                unpairedMt5Count = mt5.size,
            )
        }

        val deltas = mutableListOf<BigDecimal>()
        val skews = mutableListOf<Long>()
        var tvCursor = 0
        for (m in mt5WithMid) {
            while (tvCursor + 1 < tvWithMid.size && tvWithMid[tvCursor + 1].capturedAtMs <= m.capturedAtMs) {
                tvCursor += 1
            }
            val candidatePrev = tvWithMid[tvCursor]
            val candidateNext = if (tvCursor + 1 < tvWithMid.size) tvWithMid[tvCursor + 1] else null
            val prevDist = m.capturedAtMs - candidatePrev.capturedAtMs
            val nextDist = candidateNext?.let { it.capturedAtMs - m.capturedAtMs } ?: Long.MAX_VALUE
            val nearest = if (nextDist in 0 until prevDist) candidateNext!! else candidatePrev
            val tvMid = nearest.mid!!
            val mtMid = m.mid!!
            deltas.add(tvMid.subtract(mtMid).abs())
            skews.add(nearest.capturedAtMs - m.capturedAtMs)
        }

        val mean = deltas.fold(BigDecimal.ZERO) { acc, d -> acc + d }.divide(BigDecimal(deltas.size), MC)
        val sorted = deltas.sorted()
        val p95Idx = (sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)
        val p95 = sorted[p95Idx]
        val maxDelta = sorted.last()
        val refMid =
            mt5WithMid
                .map { it.mid!! }
                .fold(BigDecimal.ZERO) { acc, m -> acc + m }
                .divide(BigDecimal(mt5WithMid.size), MC)
        val meanRelBps =
            if (refMid.signum() == 0) {
                BigDecimal.ZERO
            } else {
                mean.divide(refMid, MC).multiply(BigDecimal(10_000), MC).setScale(2, RoundingMode.HALF_UP)
            }
        val medianSkew = skews.sorted().let { it[it.size / 2] }

        return TicksParityStats(
            tv = tvStats,
            mt5 = mt5Stats,
            pairedCount = deltas.size,
            meanMidDelta = mean.setScale(8, RoundingMode.HALF_UP),
            p95MidDelta = p95,
            maxMidDelta = maxDelta,
            meanRelativeBps = meanRelBps,
            tvAheadOfMt5Ms = medianSkew,
            unpairedTvCount = tv.size - mt5WithMid.size.coerceAtMost(tv.size),
            unpairedMt5Count = mt5.size - deltas.size,
        )
    }

    private fun stats(
        ticks: List<CapturedTick>,
        windowMs: Long,
    ): SourceStats {
        if (ticks.isEmpty()) {
            return SourceStats(0, BigDecimal.ZERO, 0L, 0L, 0L, 0L, 0L)
        }
        val gaps = ticks.zipWithNext { a, b -> b.capturedAtMs - a.capturedAtMs }
        val meanGap = if (gaps.isEmpty()) 0L else gaps.sum() / gaps.size
        val sortedGaps = gaps.sorted()
        val maxGap = sortedGaps.lastOrNull() ?: 0L
        val p95Idx = (sortedGaps.size * 95 / 100).coerceAtMost(sortedGaps.size - 1).coerceAtLeast(0)
        val p95Gap = sortedGaps.getOrNull(p95Idx) ?: 0L
        val rate =
            BigDecimal(ticks.size)
                .multiply(BigDecimal(1000))
                .divide(BigDecimal(windowMs), MC)
                .setScale(2, RoundingMode.HALF_UP)
        return SourceStats(
            count = ticks.size,
            ticksPerSec = rate,
            meanGapMs = meanGap,
            maxGapMs = maxGap,
            p95GapMs = p95Gap,
            firstAtMs = ticks.first().capturedAtMs,
            lastAtMs = ticks.last().capturedAtMs,
        )
    }
}
