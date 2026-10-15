package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.InputLine
import com.qkt.marketdata.ingest.record.DayBuckets
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.RecordBuilder
import com.qkt.marketdata.ingest.record.RowFaults
import com.qkt.marketdata.ingest.record.RowMapping
import com.qkt.marketdata.ingest.rows.InputLayout
import com.qkt.marketdata.ingest.rows.RowSkips
import com.qkt.marketdata.ingest.source.ImportInput

/** What reading one input produced, for ordering checks and the report. */
data class InputStats(
    val name: String,
    val index: Int,
    var rows: Long = 0,
    var filtered: Long = 0,
    var records: Long = 0,
    var firstMs: Long = Long.MAX_VALUE,
    var lastMs: Long = Long.MIN_VALUE,
    var forwardSteps: Long = 0,
    var backwardSteps: Long = 0,
    var firstBackward: InputLine? = null,
    var subMillis: Long = 0,
    var rounded: Long = 0,
    var sawOverlap: Boolean = false,
    var skips: RowSkips = RowSkips(),
    /** The first rows read, raw text beside the record they became. */
    val head: MutableList<Pair<String, IngestRecord>> = mutableListOf(),
    /** The last rows read, raw text beside the record they became. */
    val tail: ArrayDeque<Pair<String, IngestRecord>> = ArrayDeque(),
) {
    /** True when every step between consecutive records went back in time: a newest-first file. */
    val newestFirst: Boolean get() = backwardSteps > 0 && forwardSteps == 0L

    /** True when the file both rises and falls in time: neither oldest- nor newest-first. */
    val shuffled: Boolean get() = backwardSteps > 0 && forwardSteps > 0
}

/**
 * Reads one input through its [RowMapping] into [buckets]. A row the mapping cannot read goes to
 * [faults] (the import then refuses); a row the mapping's filter rejects is counted, not read. The order
 * key of each record is `index << 40 | line`, so ties sort in input order and files keep their order.
 */
internal class InputPass(
    private val buckets: DayBuckets,
    private val faults: RowFaults,
) {
    fun read(
        input: ImportInput,
        index: Int,
        layout: InputLayout,
        mapping: RowMapping,
    ): InputStats {
        val stats = InputStats(input.name, index)
        val builder = RecordBuilder(mapping)
        var previousMs: Long? = null
        stats.skips =
            layout.read(input) { row ->
                stats.rows++
                if (!mapping.keeps(row)) {
                    stats.filtered++
                    return@read
                }
                val record =
                    try {
                        builder.build(row, (index.toLong() shl LINE_BITS) or row.where.line)
                    } catch (e: IllegalArgumentException) {
                        faults.add(row.where, e.message ?: e.javaClass.simpleName)
                        return@read
                    } catch (e: ArithmeticException) {
                        faults.add(row.where, e.message ?: "arithmetic error")
                        return@read
                    }
                stats.records++
                val t = record.timeMs
                previousMs?.let { p ->
                    if (t > p) stats.forwardSteps++
                    if (t < p) {
                        stats.backwardSteps++
                        if (stats.firstBackward == null) stats.firstBackward = row.where
                    }
                }
                previousMs = t
                stats.firstMs = minOf(stats.firstMs, t)
                stats.lastMs = maxOf(stats.lastMs, t)
                buckets.add(record)
                val shown = row.where.raw to record
                if (stats.head.size < PREVIEW) stats.head += shown
                stats.tail.addLast(shown)
                if (stats.tail.size > PREVIEW) stats.tail.removeFirst()
            }
        stats.subMillis = mapping.time.subMillis
        stats.rounded = builder.rounded
        stats.sawOverlap = mapping.time.sawOverlap
        return stats
    }

    companion object {
        /** Bits of the order key that hold the line number. */
        const val LINE_BITS = 40
        private const val PREVIEW = 5
    }
}
