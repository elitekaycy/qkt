package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.plan.ImportTarget
import com.qkt.marketdata.ingest.plan.IngestKind
import com.qkt.marketdata.ingest.record.DayBuckets
import com.qkt.marketdata.ingest.store.ImportEntry
import com.qkt.marketdata.ingest.store.ImportProvenance
import com.qkt.marketdata.ingest.store.PointTarget
import java.nio.file.Path
import java.time.Instant

/**
 * Writes a validated funding or open-interest import into its one-file-per-contract store. A time the
 * store already holds with a different value is a clash, handled by the overlap policy (refuse lists the
 * times; skip keeps the stored values; replace overwrites them). Identical stored values are simply kept.
 */
internal class PointWrite(
    root: Path,
    private val target: ImportTarget,
    funding: Boolean,
    private val policy: OverlapPolicy,
) {
    private val store = PointTarget(root, target.qktSymbol, funding)

    fun write(
        validated: ValidateStage,
        read: ReadResult,
        entry: ImportEntry,
    ): DayOutcomes {
        val days =
            read.buckets.days.keys
                .toList()
        val records = days.flatMap { validated.clean(it) }
        val clashes = store.conflicts(records).toSet()
        if (clashes.isNotEmpty() && policy == OverlapPolicy.REFUSE) {
            throw ImportRefusal(
                "${clashes.size} time(s) are already stored for ${target.qktSymbol} with different values: " +
                    "pass --on-overlap skip or --on-overlap replace",
                clashes.sorted().map { Instant.ofEpochMilli(it).toString() },
            )
        }
        val kept = if (policy == OverlapPolicy.SKIP) records.filter { it.timeMs !in clashes } else records
        val before = store.storedCount()
        val after = store.write(kept)
        val (count, _, _) = store.readBack()
        check(count == after) { "read back $count values for ${target.qktSymbol}, store reports $after" }
        ImportProvenance.record(store.dir, entry, listOf(store.key))
        val clashDays = clashes.map { DayBuckets.dayOf(it) }.distinct().sorted()
        return DayOutcomes(
            written = days,
            skipped = if (policy == OverlapPolicy.SKIP) clashDays else emptyList(),
            replaced = if (policy == OverlapPolicy.REPLACE) clashDays else emptyList(),
        ).also { check(after >= before) { "the store shrank from $before to $after values" } }
    }

    companion object {
        /** The directory an import of [kind] for [target] writes into. */
        fun targetDir(
            root: Path,
            target: ImportTarget,
            kind: IngestKind,
            timeframe: String?,
        ): Path =
            when (kind) {
                IngestKind.TICKS -> root.resolve("symbols").resolve(target.bare)
                IngestKind.BARS, IngestKind.SERIES ->
                    root
                        .resolve("bars")
                        .resolve(target.source ?: "?")
                        .resolve(target.bare)
                        .resolve(timeframe ?: "?")
                IngestKind.FUNDING -> root.resolve("funding").resolve(target.source ?: "?")
                IngestKind.OPEN_INTEREST -> root.resolve("open_interest").resolve(target.source ?: "?")
            }
    }
}
