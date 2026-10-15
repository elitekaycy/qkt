package com.qkt.marketdata.ingest.run

import com.qkt.candles.TimeWindow
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.clock.ClockEvidence
import com.qkt.marketdata.ingest.clock.ImportClock
import com.qkt.marketdata.ingest.clock.WeeklyOpens
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.plan.MappingCompiler
import com.qkt.marketdata.ingest.record.DayBuckets
import com.qkt.marketdata.ingest.record.RowFaults
import java.time.Instant

/** The records of an import, spilled per day, with what reading them showed. */
internal class ReadResult(
    val buckets: DayBuckets,
    val stats: List<InputStats>,
    val clockEvidence: String?,
    /** Input indexes whose rows run newest first: their ties sort in reverse line order. */
    val newestFirst: Set<Int>,
) {
    /** The tie-break key for a record's input order, so newest-first files keep their own tie order. */
    fun orderKey(order: Long): Long {
        val index = (order ushr InputPass.LINE_BITS).toInt()
        if (index !in newestFirst) return order
        val line = order and LINE_MASK
        return (order - line) or (LINE_MASK - line)
    }

    private companion object {
        const val LINE_MASK = (1L shl InputPass.LINE_BITS) - 1
    }
}

/**
 * Reads every input of a plan into per-day buckets, then checks what only a full read can show: rows
 * that could not be read, files that are neither oldest- nor newest-first, tick files that overlap in
 * time (their ticks cannot be told apart), and, for FX and metals, whether the weekly opens agree with
 * the clock the times were read on.
 */
internal class ReadStage(
    private val plan: ImportPlan,
    private val request: ImportRequest,
    private val clock: ClockChoice,
    private val buckets: DayBuckets,
) {
    fun read(): ReadResult {
        val compiler = MappingCompiler(plan.descriptor, plan.target)
        val declaredTfMs = plan.descriptor.timeframe?.let { TimeWindow.parse(it).durationMs }
        val opens = WeeklyOpens().takeIf { plan.target.fxOrMetal && compiler.kind in CLOCKED }
        val faults = RowFaults()
        val pass = InputPass(buckets, faults)
        val stats =
            plan.inputs.indices.map { i ->
                val mapping = compiler.compile(plan.layouts[i], plan.formats[i], clock.clock, opens, declaredTfMs)
                pass.read(plan.inputs[i], i, plan.layouts[i], mapping)
            }
        faults.refuseIfAny()
        checkOrder(stats, compiler)
        if (compiler.kind == IngestKind.TICKS) checkTickFilesApart(stats)
        val evidence = opens?.let { checkClock(it) }
        return ReadResult(buckets, stats, evidence, stats.filter { it.newestFirst }.map { it.index }.toSet())
    }

    private fun checkOrder(
        stats: List<InputStats>,
        compiler: MappingCompiler,
    ) {
        for (s in stats) {
            if (s.shuffled && !request.sort) {
                throw ImportRefusal(
                    "${s.name} goes back in time ${s.backwardSteps} times while moving forward ${s.forwardSteps}: " +
                        "rows are out of order. Pass --sort to sort them (ties keep file order)",
                    listOfNotNull(s.firstBackward?.toString()),
                )
            }
            if (s.newestFirst && (s.sawOverlap || plan.descriptor.carryForward == true)) {
                val why = if (s.sawOverlap) "times cross a DST overlap" else "rows carry values forward"
                throw ImportRefusal(
                    "${s.name} runs newest first and its $why: " +
                        "only an oldest-first file can be read unambiguously; sort it first",
                )
            }
        }
        if (compiler.kind == IngestKind.SERIES &&
            stats.sumOf { it.records } == 0L
        ) {
            throw ImportRefusal("no values to import")
        }
    }

    private fun checkTickFilesApart(stats: List<InputStats>) {
        val sorted = stats.filter { it.records > 0 }.sortedBy { it.firstMs }
        sorted.zipWithNext().firstOrNull { (a, b) -> b.firstMs < a.lastMs }?.let { (a, b) ->
            throw ImportRefusal(
                "${a.name} and ${b.name} both hold ticks from ${Instant.ofEpochMilli(b.firstMs)} to " +
                    "${Instant.ofEpochMilli(
                        minOf(a.lastMs, b.lastMs),
                    )}: overlapping tick files cannot be de-duplicated safely; " +
                    "import them separately or trim the overlap",
            )
        }
    }

    private fun checkClock(opens: WeeklyOpens): String? {
        val evidence = ClockEvidence.of(clock.clock ?: ImportClock.Utc, opens.all)
        if (evidence.weeks.size < 2) return evidence.summary()
        if (evidence.passes || request.trustClock) return evidence.summary()
        val alternatives =
            if (clock.clock == null) {
                emptyList()
            } else {
                ClockEvidence.CANDIDATES
                    .map {
                        ClockEvidence.of(
                            it,
                            opens.all,
                        )
                    }.filter { it.passes }
                    .map { it.clock.spec }
            }
        throw ImportRefusal(
            "the weekly opens contradict the clock (${clock.how}): ${evidence.summary()}. " +
                (if (alternatives.isEmpty()) "" else "Clocks that fit: ${alternatives.joinToString()}. ") +
                "Pass the right --tz, or --trust-clock if the source really opens this way",
            evidence.outliers.take(ImportRefusal.MAX_LINES).map {
                "week opening ${Instant.ofEpochMilli(it.utcMs)} at ${it.newYorkHour}:00 New York"
            },
        )
    }

    private companion object {
        val CLOCKED = setOf(IngestKind.TICKS, IngestKind.BARS)
    }
}
