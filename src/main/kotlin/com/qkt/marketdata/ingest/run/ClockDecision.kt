package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.clock.ClockEvidence
import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.clock.WeeklyOpens
import com.qkt.marketdata.ingest.clock.agreeOver
import com.qkt.marketdata.ingest.parse.ParsedTime
import com.qkt.marketdata.ingest.plan.CellNames
import com.qkt.marketdata.ingest.record.RowTime
import com.qkt.marketdata.ingest.rows.ImportRow
import com.qkt.marketdata.ingest.rows.sampleRows

/** The clock an import reads wall-clock times on (null when its timestamps carry their own zone), and why. */
data class ClockChoice(
    val clock: ImportClock?,
    val how: String,
)

/**
 * Decides the clock of an import. Timestamps with their own zone need none, and a `--tz` given for them
 * refuses (it would mean nothing). Wall-clock timestamps use the stated clock; with none stated, an FX or
 * metals symbol's weekly opens are tried on every candidate clock and the import goes ahead only when
 * exactly one fits. Anything else refuses and asks for `--tz`.
 */
internal class ClockDecision(
    private val plan: ImportPlan,
    private val request: ImportRequest,
) {
    fun decide(): ClockChoice {
        val samples = plan.inputs.indices.flatMap { i -> sampleTimes(i, SAMPLE) }
        if (samples.isNotEmpty() && samples.all { it is ParsedTime.Date }) return dates()
        val naive = samples.any { it is ParsedTime.Local }
        if (!naive) {
            if (request.overrides.clock != null) {
                throw ImportRefusal(
                    "the timestamps carry their own zone (epoch, Z or an offset), so --tz would change nothing; drop it",
                )
            }
            return ClockChoice(null, "timestamps carry their own zone")
        }
        val broker = plan.descriptor.clock.equals(BROKER, ignoreCase = true)
        plan.descriptor.clock?.takeUnless { broker }?.let { text ->
            val clock =
                try {
                    ImportClock.parse(text)
                } catch (e: IllegalArgumentException) {
                    throw ImportRefusal(e.message ?: "invalid clock '$text'")
                }
            val by = if (request.overrides.clock != null) "--tz" else "preset ${plan.descriptor.id}"
            return ClockChoice(clock, "stated by $by")
        }
        val hint = if (broker) " $BROKER_HINT" else ""
        if (!plan.target.fxOrMetal) {
            throw ImportRefusal(
                "the timestamps have no zone and nothing says which clock they are on; pass --tz (UTC, +02:00, " +
                    "ny-close or an IANA zone).$hint",
            )
        }
        val opens = WeeklyOpens()
        plan.inputs.indices.forEach { i -> scanOpens(i, opens) }
        val evidence = ClockEvidence.CANDIDATES.map { ClockEvidence.of(it, opens.all) }
        val fitting = evidence.filter { it.passes && it.spansSeasons }
        if (fitting.size == 1) return ClockChoice(fitting.single().clock, "inferred: ${fitting.single().summary()}")
        // One DST season only: the clocks that fit may still put every row of this file at the same instant
        // (ny-close and +03:00 through a US summer), and then which of them is the broker's changes nothing here.
        val seasonal = evidence.filter { it.passes }
        val span = opens.span
        if (fitting.isEmpty() && seasonal.isNotEmpty() && span != null && agreeOver(seasonal.map { it.clock }, span)) {
            val first = seasonal.first()
            val same = seasonal.drop(1).joinToString { it.clock.spec }
            return ClockChoice(
                first.clock,
                "inferred: ${first.summary()}" +
                    if (same.isEmpty()) "" else "; $same give the same times over these dates",
            )
        }
        throw ImportRefusal(
            "the timestamps have no zone and the weekly opens do not single out one clock; pass --tz. " +
                (
                    if (seasonal.isEmpty()) {
                        "No candidate fits."
                    } else {
                        "Fitting: ${seasonal.joinToString {
                            it.clock.spec
                        }}."
                    }
                ) +
                hint,
            evidence.map { it.summary() + if (it.passes) " ✓" else "" },
        )
    }

    /**
     * Calendar dates name a day without saying whose midnight starts it. qkt's daily bars are UTC days, so
     * dates on any other clock (a broker's D1 bars run from 21:00 or 22:00 UTC) would put every bar's prices
     * in the wrong day: refused, with the fix. Undeclared dates are accepted as UTC days except for FX and
     * metals, which trade through UTC midnight and whose dates are almost always a broker's or a vendor's.
     */
    private fun dates(): ClockChoice {
        val broker = plan.descriptor.clock.equals(BROKER, ignoreCase = true)
        val stated =
            plan.descriptor.clock
                ?.takeUnless { broker }
                ?.let(::parseClock)
        val utc = stated == ImportClock.Utc
        if (broker || (stated != null && !utc) || (stated == null && plan.target.fxOrMetal)) {
            val whose = stated?.let { "the ${it.spec} clock" } ?: "a clock the file does not name"
            throw ImportRefusal(
                "the bars are dated by day only, on $whose; qkt's daily bars are UTC days, so each bar would hold " +
                    "another day's prices. Import H1 or finer bars and let qkt build 1d, or pass --tz UTC if these " +
                    "dates really are UTC days",
            )
        }
        return ClockChoice(null, if (utc) "UTC calendar days" else "calendar days (session dates)")
    }

    private fun parseClock(text: String): ImportClock =
        try {
            ImportClock.parse(text)
        } catch (e: IllegalArgumentException) {
            throw ImportRefusal(e.message ?: "invalid clock '$text'")
        }

    private fun sampleTimes(
        index: Int,
        count: Int,
    ): List<ParsedTime> {
        val time = timeOf(index, null)
        return sampleRows(plan.layouts[index], plan.inputs[index], count).mapNotNull { parsed(time, it) }
    }

    private fun scanOpens(
        index: Int,
        opens: WeeklyOpens,
    ) {
        val time = timeOf(index, opens)
        plan.layouts[index].read(plan.inputs[index]) { parsed(time, it) }
    }

    private fun timeOf(
        index: Int,
        opens: WeeklyOpens?,
    ): RowTime {
        val names = CellNames(plan.layouts[index])
        return RowTime(plan.descriptor.time.map(names::cell), plan.formats[index], null, opens)
    }

    private fun parsed(
        time: RowTime,
        row: ImportRow,
    ): ParsedTime? =
        try {
            time.parse(row)
        } catch (_: IllegalArgumentException) {
            null
        }

    private companion object {
        const val SAMPLE = 200

        /** A preset's clock for MetaTrader files: the broker's server time, which only the file or the user knows. */
        const val BROKER = "broker"
        const val BROKER_HINT =
            "MetaTrader files are on the broker's server time (Exness: UTC; IC Markets, Pepperstone, FTMO and most " +
                "others: ny-close, i.e. GMT+2/+3 with US DST); MetaTrader shows it as the Market Watch clock."
    }
}
