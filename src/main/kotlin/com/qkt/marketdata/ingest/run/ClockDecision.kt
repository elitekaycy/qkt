package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.clock.ClockEvidence
import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.clock.WeeklyOpens
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
        val naive = plan.inputs.indices.any { i -> sampleTimes(i, SAMPLE).any { it is ParsedTime.Local } }
        if (!naive) {
            if (request.overrides.clock != null) {
                throw ImportRefusal(
                    "the timestamps carry their own zone (epoch, Z or an offset), so --tz would change nothing; drop it",
                )
            }
            return ClockChoice(null, "timestamps carry their own zone")
        }
        plan.descriptor.clock?.let { text ->
            val clock =
                try {
                    ImportClock.parse(text)
                } catch (e: IllegalArgumentException) {
                    throw ImportRefusal(e.message ?: "invalid clock '$text'")
                }
            val by = if (request.overrides.clock != null) "--tz" else "preset ${plan.descriptor.id}"
            return ClockChoice(clock, "stated by $by")
        }
        if (!plan.target.fxOrMetal) {
            throw ImportRefusal(
                "the timestamps have no zone and nothing says which clock they are on; pass --tz (UTC, +02:00, ny-close or an IANA zone)",
            )
        }
        val opens = WeeklyOpens()
        plan.inputs.indices.forEach { i -> scanOpens(i, opens) }
        val evidence = ClockEvidence.CANDIDATES.map { ClockEvidence.of(it, opens.all) }
        val fitting = evidence.filter { it.passes && it.spansSeasons }
        if (fitting.size == 1) return ClockChoice(fitting.single().clock, "inferred: ${fitting.single().summary()}")
        throw ImportRefusal(
            "the timestamps have no zone and the weekly opens do not single out one clock; pass --tz. " +
                if (fitting.isEmpty()) "No candidate fits." else "Fitting: ${fitting.joinToString { it.clock.spec }}.",
            evidence.map { it.summary() + if (it.passes) " ✓" else "" },
        )
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
    }
}
