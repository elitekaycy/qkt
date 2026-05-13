package com.qkt.tools.parity

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant

data class AlignedBar(
    val startTime: Long,
    val tv: Candle,
    val mt5: Candle,
) {
    val openDelta: BigDecimal get() = (tv.open - mt5.open).abs()
    val highDelta: BigDecimal get() = (tv.high - mt5.high).abs()
    val lowDelta: BigDecimal get() = (tv.low - mt5.low).abs()
    val closeDelta: BigDecimal get() = (tv.close - mt5.close).abs()
}

data class ParityStats(
    val tvCount: Int,
    val mt5Count: Int,
    val alignedCount: Int,
    val onlyInTv: List<Long>,
    val onlyInMt5: List<Long>,
    val meanCloseDelta: BigDecimal,
    val maxCloseDelta: BigDecimal,
    val p95CloseDelta: BigDecimal,
    val maxCloseDeltaAt: Long?,
    val meanRelativeCloseBps: BigDecimal,
    val aligned: List<AlignedBar>,
) {
    fun toMarkdown(
        title: String,
        generatedAt: Instant,
    ): String =
        buildString {
            appendLine("# $title")
            appendLine()
            appendLine("Generated: $generatedAt")
            appendLine()
            appendLine("## Coverage")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("| --- | --- |")
            appendLine("| TV bars | $tvCount |")
            appendLine("| MT5 bars | $mt5Count |")
            appendLine("| Aligned | $alignedCount |")
            appendLine("| Only in TV | ${onlyInTv.size} |")
            appendLine("| Only in MT5 | ${onlyInMt5.size} |")
            appendLine()
            appendLine("## Close-price parity")
            appendLine()
            appendLine("| Metric | Value |")
            appendLine("| --- | --- |")
            appendLine("| Mean abs(close_tv − close_mt5) | ${meanCloseDelta.toPlainString()} |")
            appendLine("| p95 abs(close_tv − close_mt5) | ${p95CloseDelta.toPlainString()} |")
            appendLine("| Max abs(close_tv − close_mt5) | ${maxCloseDelta.toPlainString()} |")
            appendLine("| Mean relative (bps) | ${meanRelativeCloseBps.toPlainString()} |")
            if (maxCloseDeltaAt != null) {
                appendLine("| Max delta at | ${Instant.ofEpochMilli(maxCloseDeltaAt)} |")
            }
            appendLine()
            if (onlyInTv.isNotEmpty() || onlyInMt5.isNotEmpty()) {
                appendLine("## Unaligned bars (first 10 of each)")
                appendLine()
                appendLine("Only in TV: ${onlyInTv.take(10).joinToString { Instant.ofEpochMilli(it).toString() }}")
                appendLine()
                appendLine("Only in MT5: ${onlyInMt5.take(10).joinToString { Instant.ofEpochMilli(it).toString() }}")
                appendLine()
            }
            appendLine("## Per-bar (last 20 aligned)")
            appendLine()
            appendLine("| time | tv close | mt5 close | abs Δclose | abs Δopen | abs Δhigh | abs Δlow |")
            appendLine("| --- | --- | --- | --- | --- | --- | --- |")
            for (b in aligned.takeLast(20)) {
                appendLine(
                    "| ${Instant.ofEpochMilli(b.startTime)} | ${b.tv.close.toPlainString()} | " +
                        "${b.mt5.close.toPlainString()} | ${b.closeDelta.toPlainString()} | " +
                        "${b.openDelta.toPlainString()} | ${b.highDelta.toPlainString()} | " +
                        "${b.lowDelta.toPlainString()} |",
                )
            }
        }
}

object BarsParity {
    private val MC = MathContext(12, RoundingMode.HALF_UP)

    fun compare(
        tv: List<Candle>,
        mt5: List<Candle>,
        timestampToleranceMs: Long = 0L,
    ): ParityStats {
        val tvByStart = tv.associateBy { roundToTolerance(it.startTime, timestampToleranceMs) }
        val mt5ByStart = mt5.associateBy { roundToTolerance(it.startTime, timestampToleranceMs) }

        val aligned = mutableListOf<AlignedBar>()
        for ((bucket, tvBar) in tvByStart) {
            val mt5Bar = mt5ByStart[bucket] ?: continue
            aligned.add(AlignedBar(startTime = tvBar.startTime, tv = tvBar, mt5 = mt5Bar))
        }
        aligned.sortBy { it.startTime }

        val onlyInTv =
            tvByStart.keys
                .subtract(mt5ByStart.keys)
                .map { tvByStart.getValue(it).startTime }
                .sorted()
        val onlyInMt5 =
            mt5ByStart.keys
                .subtract(tvByStart.keys)
                .map { mt5ByStart.getValue(it).startTime }
                .sorted()

        if (aligned.isEmpty()) {
            return ParityStats(
                tvCount = tv.size,
                mt5Count = mt5.size,
                alignedCount = 0,
                onlyInTv = onlyInTv,
                onlyInMt5 = onlyInMt5,
                meanCloseDelta = BigDecimal.ZERO,
                maxCloseDelta = BigDecimal.ZERO,
                p95CloseDelta = BigDecimal.ZERO,
                maxCloseDeltaAt = null,
                meanRelativeCloseBps = BigDecimal.ZERO,
                aligned = emptyList(),
            )
        }

        val deltas = aligned.map { it.closeDelta }
        val mean = deltas.fold(BigDecimal.ZERO) { acc, d -> acc + d }.divide(BigDecimal(deltas.size), MC)
        val maxIdx = deltas.withIndex().maxBy { it.value }.index
        val max = deltas[maxIdx]
        val maxAt = aligned[maxIdx].startTime
        val sorted = deltas.sorted()
        val p95Idx = (sorted.size * 95 / 100).coerceAtMost(sorted.size - 1)
        val p95 = sorted[p95Idx]
        val meanRefClose =
            aligned
                .map { it.mt5.close }
                .fold(BigDecimal.ZERO) { acc, c -> acc + c }
                .divide(BigDecimal(aligned.size), MC)
        val meanRelBps =
            if (meanRefClose.signum() == 0) {
                BigDecimal.ZERO
            } else {
                mean.divide(meanRefClose, MC).multiply(BigDecimal(10_000), MC).setScale(2, RoundingMode.HALF_UP)
            }

        return ParityStats(
            tvCount = tv.size,
            mt5Count = mt5.size,
            alignedCount = aligned.size,
            onlyInTv = onlyInTv,
            onlyInMt5 = onlyInMt5,
            meanCloseDelta = mean.setScale(8, RoundingMode.HALF_UP),
            maxCloseDelta = max,
            p95CloseDelta = p95,
            maxCloseDeltaAt = maxAt,
            meanRelativeCloseBps = meanRelBps,
            aligned = aligned,
        )
    }

    private fun roundToTolerance(
        ms: Long,
        toleranceMs: Long,
    ): Long = if (toleranceMs <= 0L) ms else (ms / toleranceMs) * toleranceMs
}
