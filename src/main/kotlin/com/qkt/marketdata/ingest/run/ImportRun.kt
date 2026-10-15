package com.qkt.marketdata.ingest.run

import com.qkt.common.Clock
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.plan.DescriptorYaml
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.record.DayBuckets
import com.qkt.marketdata.ingest.store.BarDayTarget
import com.qkt.marketdata.ingest.store.ImportEntry
import com.qkt.marketdata.ingest.store.ImportedInput
import com.qkt.marketdata.ingest.store.StoreReadBack
import com.qkt.marketdata.ingest.store.TickDayTarget
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.HexFormat

/**
 * Runs one `qkt data import`: plan, decide the clock, read every input, validate every day, then (unless
 * a dry run) write, read back and record provenance. Every refusal happens before the first write, so a
 * refused import leaves the store exactly as it was. Scratch files live in a temporary directory removed
 * on the way out.
 */
class ImportRun(
    private val request: ImportRequest,
    private val registry: InstrumentRegistry,
    private val clock: Clock,
) {
    /** The report of the import; throws [ImportRefusal] when it cannot be done without guessing or harm. */
    fun run(): ImportReport {
        val plan = ImportPlanner(registry, clock.now() + DAY_MS).plan(request)
        val kind =
            plan.descriptor.kind
                ?: throw ImportRefusal(
                    "no preset fits and no --kind was given (ticks, bars, funding, open-interest, series)",
                )
        val clockChoice = ClockDecision(plan, request).decide()
        val scratch = Files.createTempDirectory("qkt-import")
        try {
            DayBuckets(scratch).use { buckets ->
                val read = ReadStage(plan, request, clockChoice, buckets).read()
                val validated = ValidateStage(read, kind, plan.descriptor.timeframe, request.keepLastOnConflict)
                validated.validate()
                val tf = validated.timeframe?.canonicalSpec()
                val resolved =
                    plan.descriptor.copy(
                        timeFormat =
                            plan.formats
                                .map { it.spec }
                                .distinct()
                                .singleOrNull() ?: plan.descriptor.timeFormat,
                        clock = clockChoice.clock?.spec,
                        timeframe = tf ?: plan.descriptor.timeframe,
                        match = null,
                    )
                val yaml = DescriptorYaml.encode(resolved)
                val entry = entry(plan, read, kind, yaml, tf, clockChoice)
                val outcomes =
                    if (request.dryRun || request.planOnly) {
                        DayOutcomes()
                    } else {
                        write(plan, kind, validated, read, entry)
                    }
                return ImportReport(
                    symbol = plan.target.qktSymbol,
                    kind = kind.spec,
                    preset = plan.descriptor.id,
                    presetSource = plan.presetSource,
                    description = plan.descriptor.description,
                    inputs = read.stats,
                    timeFormats = plan.formats.map { it.spec }.distinct(),
                    clock = clockChoice.clock?.spec?.let { "$it (${clockChoice.how})" } ?: clockChoice.how,
                    clockEvidence = read.clockEvidence,
                    timeframe = tf,
                    basis = ImportNotes.basis(plan.descriptor, kind),
                    volume = plan.descriptor.volume,
                    target = PointWrite.targetDir(request.dataRoot, plan.target, kind, tf).toString(),
                    days =
                        read.buckets.days.keys
                            .toList(),
                    records = read.stats.sumOf { it.records },
                    tally = validated.tally,
                    outcomes = outcomes,
                    warnings = ImportNotes.warnings(plan, read, kind),
                    dryRun = request.dryRun || request.planOnly,
                    plan = yaml,
                )
            }
        } finally {
            scratch.toFile().deleteRecursively()
        }
    }

    private fun write(
        plan: ImportPlan,
        kind: IngestKind,
        validated: ValidateStage,
        read: ReadResult,
        entry: ImportEntry,
    ): DayOutcomes {
        val root = request.dataRoot
        val stamp = Instant.ofEpochMilli(clock.now()).toString().replace(':', '-')
        return when (kind) {
            IngestKind.TICKS -> {
                val target = TickDayTarget(root, plan.target.bare, clock)
                DayWriteStage(
                    target,
                    validated,
                    read,
                    request.onOverlap,
                    fillEmpty = false,
                    stamp,
                ).write(entry) { day, n ->
                    StoreReadBack.ticks(target.dir.resolve("$day.csv.gz"), n)
                }
            }
            IngestKind.BARS, IngestKind.SERIES -> {
                val window = validated.timeframe ?: error("bars without a timeframe")
                val source = plan.target.source ?: error("bars without a source prefix")
                val target =
                    BarDayTarget(root, source, plan.target.bare, window.canonicalSpec(), window.durationMs, clock)
                DayWriteStage(
                    target,
                    validated,
                    read,
                    request.onOverlap,
                    fillEmpty = true,
                    stamp,
                ).write(entry) { day, n ->
                    StoreReadBack.bars(target.dir.resolve("$day.csv"), window.canonicalSpec(), n)
                }
            }
            IngestKind.FUNDING, IngestKind.OPEN_INTEREST ->
                PointWrite(
                    root,
                    plan.target,
                    kind == IngestKind.FUNDING,
                    request.onOverlap,
                ).write(validated, read, entry)
        }
    }

    private fun entry(
        plan: ImportPlan,
        read: ReadResult,
        kind: IngestKind,
        yaml: String,
        tf: String?,
        clockChoice: ClockChoice,
    ): ImportEntry {
        val inputs = plan.inputs.map { ImportedInput(it.name, it.sha256) }
        val digest = MessageDigest.getInstance("SHA-256")
        inputs.forEach { digest.update("${it.name}:${it.sha256}\n".toByteArray()) }
        digest.update(yaml.toByteArray())
        return ImportEntry(
            id = HexFormat.of().formatHex(digest.digest()).take(ID_LENGTH),
            inputs = inputs,
            preset = plan.descriptor.id,
            kind = kind.spec,
            symbol = plan.target.qktSymbol,
            clock = clockChoice.clock?.spec,
            clockEvidence = read.clockEvidence,
            basis = ImportNotes.basis(plan.descriptor, kind),
            volume = plan.descriptor.volume,
            timeframe = tf,
            rows = read.stats.sumOf { it.rows },
            records = read.stats.sumOf { it.records },
            notes = ImportNotes.warnings(plan, read, kind),
            qkt = request.qktVersion,
            importedAt = Instant.ofEpochMilli(clock.now()).toString(),
        )
    }

    private companion object {
        const val DAY_MS = 86_400_000L
        const val ID_LENGTH = 16
    }
}
