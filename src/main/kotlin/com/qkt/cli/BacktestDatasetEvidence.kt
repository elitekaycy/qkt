package com.qkt.cli

import com.qkt.cli.BacktestContext.Companion.SetupError
import com.qkt.dsl.ast.StrategyAst
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceHasher
import com.qkt.marketdata.store.DatasetSnapshot
import com.qkt.marketdata.store.DatasetSnapshots
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Resolves `--dataset` into the evidence a report carries, validating the snapshot against the run. */
internal object BacktestDatasetEvidence {
    data class DatasetContext(
        val evidence: DatasetEvidence,
        val dataRoot: String?,
    )

    fun datasetContext(
        args: Args,
        strategyAsts: List<StrategyAst>,
        symbols: List<String>,
        from: Instant,
        to: Instant,
    ): DatasetContext {
        val raw = args.option("dataset") ?: return DatasetContext(mutableDatasetEvidence(args), dataRoot = null)
        val path = Path.of(raw)
        val snapshot =
            try {
                DatasetSnapshots.read(path)
            } catch (e: Exception) {
                throw SetupError("cannot read --dataset $path: ${e.message}")
            }
        validateDatasetSnapshot(path, snapshot, symbols, from, to, args.option("data-root")?.let(Path::of))
        validateDatasetFieldRequirements(path, snapshot, strategyAsts)
        return DatasetContext(
            evidence =
                DatasetEvidence(
                    id = snapshot.id,
                    hash = EvidenceHasher.sha256(path),
                    qualityPolicy = snapshot.qualityPolicy.mode,
                    mutableStore = false,
                ),
            dataRoot = snapshot.dataRoot,
        )
    }

    private fun validateDatasetFieldRequirements(
        path: Path,
        snapshot: DatasetSnapshot,
        strategyAsts: List<StrategyAst>,
    ) {
        val failures = mutableListOf<String>()
        val totalTicks = snapshot.files.sumOf { it.tickCount }
        val bidAskTicks = snapshot.files.sumOf { it.bidAskTicks }
        val volumeTicks = snapshot.files.sumOf { it.volumeTicks }
        val hasBidAsk = snapshot.files.all { it.tickCount == 0 || it.bidAskTicks == it.tickCount }
        val hasVolume = snapshot.files.all { it.tickCount == 0 || it.volumeTicks == it.tickCount }
        for (ast in strategyAsts) {
            val aliasToSymbol = ast.streams.associate { it.alias to it.symbol }
            val requirements = StrategyDataRequirementScanner.scan(ast)
            val missingQuotes =
                requirements.quoteAliases
                    .filter { aliasToSymbol[it] == snapshot.symbol }
                    .sorted()
            if (missingQuotes.isNotEmpty() && !hasBidAsk) {
                failures.add(
                    "strategy reads bid/ask/spread on ${missingQuotes.joinToString()} but --dataset $path " +
                        "has bid/ask on $bidAskTicks/$totalTicks ticks",
                )
            }
            val missingVolume =
                requirements.volumeAliases
                    .filter { aliasToSymbol[it] == snapshot.symbol }
                    .sorted()
            if (missingVolume.isNotEmpty() && !hasVolume) {
                failures.add(
                    "strategy reads volume on ${missingVolume.joinToString()} but --dataset $path " +
                        "has volume on $volumeTicks/$totalTicks ticks",
                )
            }
        }
        if (failures.isNotEmpty()) {
            throw SetupError("dataset field capability check failed:\n  ${failures.joinToString("\n  ")}")
        }
    }

    private fun validateDatasetSnapshot(
        path: Path,
        snapshot: DatasetSnapshot,
        symbols: List<String>,
        from: Instant,
        to: Instant,
        dataRootOverride: Path?,
    ) {
        val bareSymbols = symbols.map { it.substringAfter(':') }.distinct()
        if (bareSymbols != listOf(snapshot.symbol)) {
            throw SetupError("--dataset $path covers ${snapshot.symbol}, but run symbols are $bareSymbols")
        }
        val runFrom = LocalDate.ofInstant(from, ZoneOffset.UTC)
        val runToExclusive = LocalDate.ofInstant(to.minusMillis(1), ZoneOffset.UTC).plusDays(1)
        val snapshotFrom = LocalDate.parse(snapshot.from)
        val snapshotTo = LocalDate.parse(snapshot.to)
        if (runFrom.isBefore(snapshotFrom) || runToExclusive.isAfter(snapshotTo)) {
            throw SetupError(
                "--dataset $path covers $snapshotFrom..$snapshotTo, but run needs $runFrom..$runToExclusive",
            )
        }
        val verification = DatasetSnapshots.verify(snapshot, dataRootOverride = dataRootOverride, strict = true)
        if (!verification.ok) {
            throw SetupError(
                "dataset snapshot verification failed:\n  ${verification.failures.joinToString("\n  ")}",
            )
        }
    }

    private fun mutableDatasetEvidence(args: Args): DatasetEvidence =
        DatasetEvidence(
            qualityPolicy =
                if (args.flag("allow-incomplete")) {
                    "allow-incomplete"
                } else {
                    "default-completeness-check"
                },
            mutableStore = true,
            warning = "Dataset is a mutable local store, not an immutable snapshot.",
        )
}
