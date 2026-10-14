package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.time.Instant
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.ln

/**
 * Flags likely bad prints in one day: a move more than [THRESHOLD] median absolute deviations from the
 * day's median non-zero move that the very next move, itself as extreme, takes back by at least [REVERSAL]
 * of its size (a price that jumps and returns, the signature of a bad tick, not of a trend), and that spans
 * at least [RANGE_SHARE] of the day's whole range (so the bid/ask bounce between trades never counts). Flags are reported, never
 * acted on: news spikes are real, and deleting them would make a strategy's risk look smaller than it is.
 * Statistics use doubles because they only rank moves; no stored value passes through them.
 */
internal object SpikeFlags {
    private const val THRESHOLD = 25.0
    private const val REVERSAL = 0.8
    private const val RANGE_SHARE = 0.2
    private const val MIN_RECORDS = 30
    private const val MAX_LISTED = 20

    fun scan(
        day: LocalDate,
        records: List<IngestRecord>,
        tally: CheckTally,
    ) {
        val prices = records.mapNotNull(::priceOf)
        if (prices.size < MIN_RECORDS) return
        val returns = prices.zipWithNext { a, b -> ln(b.second / a.second) }
        val moves = returns.filter { it != 0.0 }
        if (moves.size < MIN_RECORDS) return
        val median = moves.sorted()[moves.size / 2]
        val mad = moves.map { abs(it - median) }.sorted()[moves.size / 2]
        if (mad == 0.0) return
        val dayRange = ln(prices.maxOf { it.second } / prices.minOf { it.second })
        returns.forEachIndexed { i, r ->
            val next = returns.getOrNull(i + 1) ?: return@forEachIndexed
            val reverts = next * r < 0 && abs(next) >= REVERSAL * abs(r) && abs(next - median) / mad > THRESHOLD
            val large = abs(r - median) / mad > THRESHOLD && abs(r) >= RANGE_SHARE * dayRange
            if (large && reverts && tally.spikes.size < MAX_LISTED) {
                val at = Instant.ofEpochMilli(prices[i + 1].first)
                tally.spikes += "$day $at: ${"%+.3f".format(r * PERCENT)}% in one step"
            }
        }
    }

    private fun priceOf(r: IngestRecord): Pair<Long, Double>? =
        when (r) {
            is BarRecord -> r.timeMs to r.close.toDouble()
            is TickRecord -> {
                val p =
                    r.price?.toDouble() ?: if (r.bid != null && r.ask != null) r.bid.add(r.ask).toDouble() / 2 else null
                p?.let { r.timeMs to it }
            }
            else -> null
        }

    private const val PERCENT = 100.0
}
