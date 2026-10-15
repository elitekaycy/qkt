package com.qkt.marketdata.ingest.compare

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs

/** How two bar series of one instrument compare. Differences are in points when [point] is known, else price units. */
data class CompareResult(
    val matched: Int,
    val onlyFirst: Int,
    val onlySecond: Int,
    /** The shift (ms) applied to the second series that best aligns the closes; non-zero means a clock problem. */
    val offsetMs: Long,
    val medianClose: BigDecimal,
    val p99Close: BigDecimal,
    val maxClose: BigDecimal,
    val maxHighLow: BigDecimal,
    /** Matched bars whose open or volume differ (identical needs none). */
    val otherFieldDiffs: Int,
    val point: BigDecimal?,
) {
    /** `identical`, `matches` (same clock, p99 within two points) or `differs`. */
    val verdict: String
        get() =
            when {
                matched == 0 -> "no overlap"
                offsetMs == 0L &&
                    onlyFirst == 0 &&
                    onlySecond == 0 &&
                    maxClose.signum() == 0 &&
                    maxHighLow.signum() == 0 &&
                    otherFieldDiffs == 0 -> "identical"
                offsetMs == 0L && point != null && p99Close <= MATCH_POINTS -> "matches"
                else -> "differs"
            }

    /** One line for the report and provenance. */
    fun summary(): String {
        val unit = if (point == null) "" else " pt"
        return "$verdict: $matched bars matched, $onlyFirst only in the first, $onlySecond only in the second, " +
            "offset ${offsetMs / MINUTE_MS}m, close Δ median ${p(
                medianClose,
            )}$unit p99 ${p(p99Close)}$unit max ${p(maxClose)}$unit, " +
            "high/low Δ max ${p(maxHighLow)}$unit, $otherFieldDiffs with a different open or volume"
    }

    private fun p(v: BigDecimal) = v.stripTrailingZeros().toPlainString()

    private companion object {
        val MATCH_POINTS = BigDecimal(2)
        const val MINUTE_MS = 60_000L
    }
}

/**
 * Compares two bar series of one instrument, typically an import against a venue's own bars for the same
 * days. The clock offset is found first: the shift of the second series, in whole bars up to twelve hours
 * either way, with the smallest median close difference over a sample of bars (no shift wins ties), so a
 * wrong clock shows up as a number instead of as noise. Doubles only rank the shifts; the differences
 * reported are then measured exactly, in [point]s when the instrument's point is known.
 */
object SeriesCompare {
    private const val MAX_SHIFT_MS = 12 * 3_600_000L
    private const val SAMPLE = 4000

    /** The comparison of [first] and [second], both [timeframeMs] bars. */
    fun compare(
        first: List<Candle>,
        second: List<Candle>,
        timeframeMs: Long,
        point: BigDecimal?,
    ): CompareResult {
        val a = first.associateBy { it.startTime }
        val b = second.associateBy { it.startTime }
        val offset = bestOffset(a, b, timeframeMs)
        val pairs = a.mapNotNull { (t, bar) -> b[t + offset]?.let { bar to it } }
        val unit = point ?: BigDecimal.ONE
        val close =
            pairs
                .map { (x, y) ->
                    x.close
                        .subtract(y.close)
                        .abs()
                        .divide(unit, MathContext.DECIMAL64)
                }.sorted()
        val highLow =
            pairs.map { (x, y) ->
                maxOf(x.high.subtract(y.high).abs(), x.low.subtract(y.low).abs()).divide(unit, MathContext.DECIMAL64)
            }
        return CompareResult(
            matched = pairs.size,
            onlyFirst = a.keys.count { b[it + offset] == null },
            onlySecond = b.keys.count { a[it - offset] == null },
            offsetMs = offset,
            medianClose = close.getOrElse(close.size / 2) { BigDecimal.ZERO },
            p99Close =
                close
                    .getOrElse((close.size * 99) / 100) {
                        BigDecimal.ZERO
                    }.setScale(4, RoundingMode.HALF_EVEN)
                    .stripTrailingZeros(),
            maxClose = close.lastOrNull() ?: BigDecimal.ZERO,
            maxHighLow = highLow.maxOrNull() ?: BigDecimal.ZERO,
            otherFieldDiffs =
                pairs.count { (x, y) ->
                    x.open.compareTo(y.open) != 0 || x.volume.compareTo(y.volume) != 0
                },
            point = point,
        )
    }

    private fun bestOffset(
        a: Map<Long, Candle>,
        b: Map<Long, Candle>,
        timeframeMs: Long,
    ): Long {
        val keys = a.keys.sorted()
        val step = maxOf(1, keys.size / SAMPLE)
        val sample = keys.filterIndexed { i, _ -> i % step == 0 }
        val shifts = (-MAX_SHIFT_MS / timeframeMs..MAX_SHIFT_MS / timeframeMs).map { it * timeframeMs }
        val minPairs = maxOf(1, sample.size / MIN_OVERLAP_SHARE)
        val medianGap =
            shifts.associateWith { s ->
                val gaps =
                    sample.mapNotNull { t ->
                        b[t + s]?.let {
                            abs(
                                it.close.toDouble() - a.getValue(t).close.toDouble(),
                            )
                        }
                    }
                if (gaps.size < minPairs) Double.MAX_VALUE else gaps.sorted()[gaps.size / 2]
            }
        val best = medianGap.minOf { it.value }
        return medianGap.entries
            .filter { it.value == best }
            .minBy { abs(it.key) }
            .key
    }

    private const val MIN_OVERLAP_SHARE = 10
}
