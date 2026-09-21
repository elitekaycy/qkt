package com.qkt.cli

import com.qkt.connectivity.AccountDirectory
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.YamlInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path

/**
 * `qkt instruments pull`: copy the venue's own contract specs into an `instruments.yaml`, so a
 * backtest sizes and prices with what live trades with. Goes through the account contract, so it
 * works for any connector that reports specs.
 *
 * e.g. `qkt instruments pull --symbols EXNESS:BTCUSD,EXNESS:XAUUSD --as-prefix BACKTEST
 * --out data/instruments.yaml` writes `BACKTEST:BTCUSD` and `BACKTEST:XAUUSD`, keeping every
 * other entry already in the file.
 */
internal object InstrumentsPull {
    /** Specs for [symbols] from [accounts]; a symbol no account serves, or no venue reports, is an error. */
    fun pull(
        accounts: AccountDirectory,
        symbols: List<String>,
        asPrefix: String?,
    ): List<InstrumentMeta> =
        symbols.map { symbol ->
            val account = accounts.forSymbol(symbol) ?: error("no configured account serves $symbol")
            val spec = account.instrumentSpec(symbol) ?: error("${account.config.name} reports no spec for $symbol")
            if (asPrefix ==
                null
            ) {
                spec
            } else {
                spec.copy(qktSymbol = "${asPrefix.uppercase()}:${symbol.substringAfter(':')}")
            }
        }

    /** [pulled] merged over the entries already at [path], by symbol, sorted for a stable diff. */
    fun merge(
        path: Path,
        pulled: List<InstrumentMeta>,
    ): List<InstrumentMeta> {
        val existing = if (Files.exists(path)) YamlInstrumentRegistry.load(path).all() else emptyList()
        return (
            existing.associateBy {
                it.qktSymbol
            } + pulled.associateBy { it.qktSymbol }
        ).values.sortedBy { it.qktSymbol }
    }

    fun render(
        entries: List<InstrumentMeta>,
        source: String,
    ): String =
        buildString {
            appendLine("# Venue contract specs. Pulled entries come from: $source")
            appendLine("# Commission and swaps are not reported by the venue's symbol endpoint; set them by hand.")
            appendLine("instruments:")
            for (e in entries) {
                appendLine("  - qktSymbol: ${e.qktSymbol}")
                appendLine("    contractSize: ${e.contractSize.toPlainString()}")
                appendLine("    volumeStep: ${e.volumeStep.toPlainString()}")
                appendLine("    volumeMin: ${e.volumeMin.toPlainString()}")
                e.volumeMax?.let { appendLine("    volumeMax: ${it.toPlainString()}") }
                appendLine("    pointSize: ${e.pointSize.toPlainString()}")
                appendLine("    digits: ${e.digits}")
                appendLine("    tradeStopsLevelPoints: ${e.tradeStopsLevelPoints}")
                if (e.commissionPerLot.signum() !=
                    0
                ) {
                    appendLine("    commissionPerLot: ${e.commissionPerLot.toPlainString()}")
                }
                if (e.slippagePoints != 0) appendLine("    slippagePoints: ${e.slippagePoints}")
                if (e.swapLongPoints.signum() !=
                    0
                ) {
                    appendLine("    swapLongPoints: ${e.swapLongPoints.toPlainString()}")
                }
                if (e.swapShortPoints.signum() !=
                    0
                ) {
                    appendLine("    swapShortPoints: ${e.swapShortPoints.toPlainString()}")
                }
                if (e.swapRolloverHourUtc != 21) appendLine("    swapRolloverHourUtc: ${e.swapRolloverHourUtc}")
                if (e.swapTripleDay !=
                    java.time.DayOfWeek.WEDNESDAY
                ) {
                    appendLine("    swapTripleDay: ${e.swapTripleDay}")
                }
            }
        }

    /** The `pull` subcommand: exit code, with the YAML on stdout when no --out is given. */
    fun run(args: Args): Int {
        val symbols =
            args
                .option("symbols")
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotEmpty() }
                .orEmpty()
        if (symbols.isEmpty()) return usage("--symbols is required, e.g. --symbols EXNESS:BTCUSD,EXNESS:XAUUSD")
        val configPath =
            args.option("config")?.let(Path::of) ?: Config.locate()
                ?: return usage("no qkt.config.yaml found; pass --config <path>")
        val config = Config.load(configPath)
        val pulled =
            try {
                config.openAccounts().use { accounts -> pull(accounts, symbols, args.option("as-prefix")) }
            } catch (e: Exception) {
                return usage(e.message ?: e.toString())
            }
        val out = args.option("out")?.let(Path::of)
        val yaml = render(if (out == null) pulled else merge(out, pulled), source = configPath.toString())
        if (out == null) print(yaml) else Files.writeString(out, yaml)
        if (out != null) println("qkt: wrote ${pulled.size} instrument spec(s) to $out")
        return ExitCodes.SUCCESS
    }

    private fun usage(message: String): Int {
        System.err.println("qkt: instruments pull: $message")
        return ExitCodes.ARG_ERROR
    }
}
