package com.qkt.cli

import com.qkt.backtest.BacktestResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.evidence.AccountingEvidence
import com.qkt.evidence.DatasetEvidence
import com.qkt.evidence.EvidenceEnvelope
import com.qkt.evidence.EvidenceHasher
import java.nio.file.Files
import java.nio.file.Path

/**
 * Evidence assembly for `BacktestCommand`: envelope, config hash, and the HTML
 * reproduction record (extracted: `BacktestCommand` may only shrink).
 */
internal object BacktestEvidence {
    fun attach(
        args: Args,
        result: BacktestResult,
        path: Path,
        parsedFile: ParsedFile,
        execution: com.qkt.evidence.ExecutionEvidence,
        datasetEvidence: DatasetEvidence,
        resolved: Map<String, com.qkt.evidence.ResolvedValue>,
    ): BacktestResult =
        result.copy(
            evidence =
                EvidenceEnvelope(
                    qktVersion = BuildInfo.VERSION,
                    gitSha = BuildInfo.GIT_SHA,
                    buildTimestamp = BuildInfo.BUILD_TIMESTAMP,
                    command = args.tokens,
                    strategyHash = EvidenceHasher.sha256(path),
                    importedFileHashes = importedHashes(path, parsedFile),
                    configHash = configHash(args),
                    dataset = datasetEvidence,
                    execution = execution,
                    accounting = accountingEvidence(result.accounting),
                    resolved = resolved,
                ),
            reproduction = reproductionInfo(args, path, resolved),
        )

    /** A `report.dir` (or instruments path) relative to the config file stays relocatable. */
    internal fun resolveConfigRelative(
        cfg: Config,
        raw: String,
    ): Path {
        val candidate = Path.of(raw)
        if (candidate.isAbsolute) return candidate
        return cfg.configDir?.resolve(candidate) ?: candidate
    }

    internal fun accountingEvidence(snapshot: com.qkt.accounting.AccountingSnapshot?): AccountingEvidence? {
        if (snapshot == null) return null
        return AccountingEvidence(
            accountCurrency = snapshot.accountCurrency,
            missingPolicy = snapshot.missingPolicy,
            source = snapshot.source,
            configuredFxSymbols = snapshot.configuredSymbols,
            conversions =
                snapshot.conversions.associate { fx ->
                    "${fx.from}->${fx.to}@${fx.source}" to
                        "rate=${fx.rate.toPlainString()} timestamp=${fx.timestamp}"
                },
            costKinds = snapshot.supportedCostKinds,
            warnings = snapshot.warnings,
        )
    }

    internal fun importedHashes(
        path: Path,
        parsedFile: ParsedFile,
    ): Map<String, String> =
        when (parsedFile) {
            is ParsedFile.StrategyFile -> emptyMap()
            is ParsedFile.PortfolioFile -> {
                val parent = path.toAbsolutePath().normalize().parent ?: Path.of(".").toAbsolutePath().normalize()
                parsedFile.ast.imports.associate { imp ->
                    imp.alias to EvidenceHasher.sha256(parent.resolve(imp.path).toAbsolutePath().normalize())
                }
            }
        }

    internal fun configHash(args: Args): String? {
        val path = Config.resolvePath(args.option("config"))
        return if (Files.exists(path)) EvidenceHasher.sha256(path) else null
    }
}
