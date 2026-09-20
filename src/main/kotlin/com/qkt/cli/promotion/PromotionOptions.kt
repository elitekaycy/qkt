package com.qkt.cli.promotion

import com.qkt.cli.ArgError
import com.qkt.cli.Args
import com.qkt.cli.Config
import com.qkt.cli.PaperValidationMetrics
import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionStore
import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path

/** Reads the `qkt promotion` flags shared by its actions: strategy path, config, store, evidence and paper metrics. */
internal class PromotionOptions(
    private val args: Args,
) {
    /** The promotion gate section of the resolved `--config`. */
    fun promotionConfig(): PromotionGateConfig {
        val cfgPath = Config.resolvePath(args.option("config"))
        return Config.load(cfgPath).promotionGateConfig
    }

    /** The ledger at `--registry-dir`, else the configured registry, else the state directory's. */
    fun promotionStore(config: PromotionGateConfig): PromotionStore {
        val root =
            args.option("registry-dir")?.let(Path::of)
                ?: config.registryDir
                ?: StateDir.resolve(args.option("state-dir")).stateRoot.resolve("promotion")
        return PromotionStore(root)
    }

    /** The absolute strategy path; a missing file is an argument error. */
    fun strategyPath(raw: String): Path {
        val path = Path.of(raw).toAbsolutePath()
        if (!Files.exists(path)) throw ArgError("file not found: $raw")
        return path
    }

    /** `--evidence key=value` pairs, comma-separated or repeated. */
    fun parseEvidence(): Map<String, String> =
        parseCsv(args.options("evidence"))
            .associate { item ->
                val i = item.indexOf('=')
                if (i <= 0 || i == item.lastIndex) throw ArgError("--evidence must use key=value")
                item.substring(0, i).trim() to item.substring(i + 1).trim()
            }

    /** Paper metrics from the flags, falling back field by field to [existing]; [existing] when none are given. */
    fun parsePaper(existing: PaperValidationMetrics?): PaperValidationMetrics? {
        val supplied =
            listOf(
                "paper-days",
                "paper-trades",
                "avg-slippage-bps",
                "p95-slippage-bps",
                "rejection-rate-pct",
                "missed-fills",
                "paper-status",
            ).any { args.option(it) != null }
        if (!supplied) return existing
        return PaperValidationMetrics(
            days = intOption("paper-days") ?: existing?.days ?: 0,
            trades = intOption("paper-trades") ?: existing?.trades ?: 0,
            avgSlippageBps = doubleOption("avg-slippage-bps") ?: existing?.avgSlippageBps,
            p95SlippageBps = doubleOption("p95-slippage-bps") ?: existing?.p95SlippageBps,
            rejectionRatePct = doubleOption("rejection-rate-pct") ?: existing?.rejectionRatePct,
            missedFills = intOption("missed-fills") ?: existing?.missedFills,
            status = args.option("paper-status") ?: existing?.status,
        )
    }

    /** Splits comma-separated flag values into trimmed, non-blank items. */
    fun parseCsv(values: List<String>): List<String> =
        values
            .flatMap { it.split(',') }
            .map { it.trim() }
            .filter { it.isNotBlank() }

    private fun intOption(name: String): Int? =
        args.option(name)?.toIntOrNull()?.takeIf { it >= 0 }
            ?: args.option(name)?.let { throw ArgError("--$name must be a non-negative integer") }

    private fun doubleOption(name: String): Double? =
        args.option(name)?.toDoubleOrNull()?.takeIf { it >= 0.0 }
            ?: args.option(name)?.let { throw ArgError("--$name must be a non-negative number") }
}
