package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.time.LocalDate

/**
 * Validates and cleans one UTC day of sorted records, the same way on every pass so the records checked
 * are exactly the records written. Values are never altered: a record is kept, dropped as an exact
 * duplicate or a crossed quote (both counted), or reported as a problem that refuses the import.
 * [timeframeMs] is the bar size; every bar must sit on its epoch-aligned UTC grid.
 */
class DayChecks(
    private val tally: CheckTally,
    private val timeframeMs: Long? = null,
) {
    /** [records] of [day], cleaned. */
    fun clean(
        day: LocalDate,
        records: List<IngestRecord>,
    ): List<IngestRecord> {
        val out = ArrayList<IngestRecord>(records.size)
        for (r in records) {
            val previous = out.lastOrNull()
            when (r) {
                is TickRecord -> if (tick(day, r)) out += r
                is BarRecord -> if (bar(day, r, previous as? BarRecord)) out += r
                is FundingRecord ->
                    if (sameTime(
                            day,
                            r,
                            previous,
                            r.copy(order = 0) == (previous as? FundingRecord)?.copy(order = 0),
                        )
                    ) {
                        out += r
                    }
                is OpenInterestRecord ->
                    if (sameTime(
                            day,
                            r,
                            previous,
                            r.copy(order = 0) == (previous as? OpenInterestRecord)?.copy(order = 0),
                        )
                    ) {
                        out += r
                    }
                is SeriesRecord -> series(r, previous as? SeriesRecord, out)
            }
        }
        return out
    }

    private fun tick(
        day: LocalDate,
        t: TickRecord,
    ): Boolean {
        listOf("price" to t.price, "bid" to t.bid, "ask" to t.ask).forEach { (name, v) ->
            if (v != null && v.signum() <= 0) tally.problem(day, t.timeMs, "$name $v is not positive")
        }
        listOf("volume" to t.volume, "bidVolume" to t.bidVolume, "askVolume" to t.askVolume).forEach { (name, v) ->
            if (v != null && v.signum() < 0) tally.problem(day, t.timeMs, "$name $v is negative")
        }
        if (t.bid != null && t.ask != null && t.bid > t.ask) {
            tally.crossedDropped++
            return false
        }
        return true
    }

    private fun bar(
        day: LocalDate,
        b: BarRecord,
        previous: BarRecord?,
    ): Boolean {
        if (previous != null && previous.timeMs == b.timeMs) {
            if (previous.copy(order = 0) == b.copy(order = 0)) {
                tally.duplicatesDropped++
            } else {
                tally.problem(day, b.timeMs, "two different bars open at the same time")
            }
            return false
        }
        if (listOf(b.open, b.high, b.low, b.close).any {
                it.signum() <= 0
            }
        ) {
            tally.problem(day, b.timeMs, "a price is not positive")
        }
        if (b.low > b.open.min(b.close) || b.high < b.open.max(b.close) || b.low > b.high) {
            tally.problem(
                day,
                b.timeMs,
                "open ${b.open} high ${b.high} low ${b.low} close ${b.close} is not a valid bar",
            )
        }
        if (b.volume.signum() < 0) tally.problem(day, b.timeMs, "volume ${b.volume} is negative")
        val tf = timeframeMs
        if (tf != null && Math.floorMod(b.timeMs, tf) != 0L) {
            tally.problem(
                day,
                b.timeMs,
                "the bar is not on the UTC ${tf / MINUTE_MS}-minute grid (wrong clock, or a close-time label?)",
            )
        }
        return true
    }

    private fun sameTime(
        day: LocalDate,
        r: IngestRecord,
        previous: IngestRecord?,
        identical: Boolean,
    ): Boolean {
        if (previous == null || previous.timeMs != r.timeMs) return true
        if (identical) {
            tally.duplicatesDropped++
        } else {
            tally.problem(
                day,
                r.timeMs,
                "two different values at the same time",
            )
        }
        return false
    }

    private fun series(
        r: SeriesRecord,
        previous: SeriesRecord?,
        out: MutableList<IngestRecord>,
    ) {
        if (previous == null || previous.timeMs != r.timeMs) {
            out += r
            return
        }
        if (previous.observedMs == r.observedMs && previous.value.compareTo(r.value) == 0) {
            tally.duplicatesDropped++
            return
        }
        tally.superseded++
        if (r.observedMs > previous.observedMs) out[out.size - 1] = r
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
