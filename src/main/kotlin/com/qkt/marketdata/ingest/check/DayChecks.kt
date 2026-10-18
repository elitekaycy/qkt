package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.record.BarRecord
import com.qkt.marketdata.ingest.record.FundingRecord
import com.qkt.marketdata.ingest.record.IngestRecord
import com.qkt.marketdata.ingest.record.OpenInterestRecord
import com.qkt.marketdata.ingest.record.SeriesRecord
import com.qkt.marketdata.ingest.record.TickRecord
import java.time.Instant
import java.time.LocalDate

/**
 * Validates and cleans one UTC day of sorted records, the same way on every pass so the records checked
 * are exactly the records written. Values are never altered: a record is kept, dropped as an exact
 * duplicate or a crossed quote (both counted), or reported as a problem that refuses the import.
 * [timeframeMs] is the bar size; every bar must sit on its epoch-aligned UTC grid. Two different bars,
 * rates or figures at one time are a problem unless [keepLast] (`--on-conflict last`), which keeps the one
 * read later (files are read in name order) and lists the time. An open-interest figure of exactly zero is
 * dropped and counted: venues write zeros during outages, and no traded contract has none.
 */
class DayChecks(
    private val tally: CheckTally,
    private val timeframeMs: Long? = null,
    private val keepLast: Boolean = false,
) {
    private enum class Verdict { KEEP, DROP, REPLACE }

    /** [records] of [day], cleaned. */
    fun clean(
        day: LocalDate,
        records: List<IngestRecord>,
    ): List<IngestRecord> {
        val out = ArrayList<IngestRecord>(records.size)
        for (r in records) {
            val previous = out.lastOrNull()
            val verdict =
                when (r) {
                    is TickRecord -> tick(day, r)
                    is SeriesRecord -> series(r, previous as? SeriesRecord)
                    is OpenInterestRecord -> openInterest(day, r) ?: (sameTime(day, r, previous) ?: Verdict.KEEP)
                    else ->
                        (sameTime(day, r, previous) ?: Verdict.KEEP).also {
                            if (r is BarRecord &&
                                it != Verdict.DROP
                            ) {
                                bar(day, r)
                            }
                        }
                }
            when (verdict) {
                Verdict.KEEP -> out += r
                Verdict.REPLACE -> out[out.size - 1] = r
                Verdict.DROP -> Unit
            }
        }
        return out
    }

    private fun tick(
        day: LocalDate,
        t: TickRecord,
    ): Verdict {
        listOf("price" to t.price, "bid" to t.bid, "ask" to t.ask).forEach { (name, v) ->
            if (v != null && v.signum() <= 0) tally.problem(day, t.timeMs, "$name $v is not positive")
        }
        listOf("volume" to t.volume, "bidVolume" to t.bidVolume, "askVolume" to t.askVolume).forEach { (name, v) ->
            if (v != null && v.signum() < 0) tally.problem(day, t.timeMs, "$name $v is negative")
        }
        if (t.bid != null && t.ask != null && t.bid > t.ask) {
            tally.crossedDropped++
            return Verdict.DROP
        }
        return Verdict.KEEP
    }

    private fun bar(
        day: LocalDate,
        b: BarRecord,
    ) {
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
            val past = Math.floorMod(b.timeMs, tf)
            val by = if (past % MINUTE_MS == 0L) "${past / MINUTE_MS}m" else "${past}ms"
            tally.problem(
                day,
                b.timeMs,
                "the bar starts $by past the UTC ${tf / MINUTE_MS}-minute grid: bars anchored to a session open " +
                    "(Yahoo's hourly bars start at :30), a close-time label or the wrong clock; import a finer " +
                    "timeframe (1m) and qkt builds this one on the UTC grid",
            )
        }
    }

    /** Null to go on checking, or the verdict for a figure that is not a usable open interest. */
    private fun openInterest(
        day: LocalDate,
        r: OpenInterestRecord,
    ): Verdict? {
        if (r.openInterest.signum() < 0) {
            tally.problem(day, r.timeMs, "open interest ${r.openInterest} is negative")
            return Verdict.DROP
        }
        if (r.openInterest.signum() > 0) return null
        tally.zeroOpenInterestDropped++
        return Verdict.DROP
    }

    /** The verdict for a bar, rate or figure at the same time as [previous], or null when the times differ. */
    private fun sameTime(
        day: LocalDate,
        r: IngestRecord,
        previous: IngestRecord?,
    ): Verdict? {
        if (previous == null || previous.timeMs != r.timeMs) return null
        if (withoutOrder(previous) == withoutOrder(r)) {
            tally.duplicatesDropped++
            return Verdict.DROP
        }
        val what = if (r is BarRecord) "two different bars open at the same time" else SAME_TIME
        if (!keepLast) {
            tally.problem(day, r.timeMs, "$what (pass --on-conflict last to keep the later file's)")
            return Verdict.DROP
        }
        tally.conflictsKeptLast += "${Instant.ofEpochMilli(r.timeMs)}: $what"
        return Verdict.REPLACE
    }

    private fun withoutOrder(r: IngestRecord): IngestRecord =
        when (r) {
            is BarRecord -> r.copy(order = 0)
            is FundingRecord -> r.copy(order = 0)
            is OpenInterestRecord -> r.copy(order = 0)
            is TickRecord -> r.copy(order = 0)
            is SeriesRecord -> r.copy(order = 0)
        }

    private fun series(
        r: SeriesRecord,
        previous: SeriesRecord?,
    ): Verdict {
        if (previous == null || previous.timeMs != r.timeMs) return Verdict.KEEP
        if (previous.observedMs == r.observedMs && previous.value.compareTo(r.value) == 0) {
            tally.duplicatesDropped++
            return Verdict.DROP
        }
        tally.superseded++
        return if (r.observedMs > previous.observedMs) Verdict.REPLACE else Verdict.DROP
    }

    private companion object {
        const val MINUTE_MS = 60_000L
        const val SAME_TIME = "two different values at the same time"
    }
}
