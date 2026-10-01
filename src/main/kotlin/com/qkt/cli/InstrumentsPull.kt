package com.qkt.cli

import com.qkt.connectivity.AccountDirectory
import com.qkt.instrument.VenueInstrumentSpec
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
    ): List<VenueInstrumentSpec> =
        symbols.map { symbol ->
            val account = accounts.forSymbol(symbol) ?: error("no configured account serves $symbol")
            val spec = account.instrumentSpec(symbol) ?: error("${account.config.name} reports no spec for $symbol")
            if (asPrefix == null) {
                spec
            } else {
                spec.copy(meta = spec.meta.copy(qktSymbol = "${asPrefix.uppercase()}:${symbol.substringAfter(':')}"))
            }
        }

    /**
     * Writes [pulled] into the `instruments:` entries at [path]: each pulled symbol's entry is
     * rewritten, keeping what it sets by hand ([keeping]); every other entry, and every other
     * top-level section of the file (such as `futures:`), stays exactly as it was. Entries are sorted
     * by symbol for a stable diff.
     */
    fun write(
        path: Path,
        pulled: List<VenueInstrumentSpec>,
        source: String,
    ) {
        val text = if (Files.exists(path)) Files.readString(path) else ""
        val blocks = InstrumentEntryBlocks.of(text)
        val existing = if (Files.exists(path)) YamlInstrumentRegistry.load(path).all() else emptyList()
        val entries =
            existing.associate {
                it.qktSymbol to (
                    blocks[it.qktSymbol] ?: VenueInstrumentSpec(
                        it,
                    ).entryText()
                )
            }
        val bySymbol = existing.associateBy { it.qktSymbol }
        val fresh =
            pulled.associate {
                it.meta.qktSymbol to
                    it
                        .keeping(
                            bySymbol[it.meta.qktSymbol],
                            blocks,
                        ).entryText()
            }
        val body = (entries + fresh).toSortedMap().values.joinToString("")
        Files.writeString(path, header(source) + body + YamlSections.except(text, "instruments"))
    }

    /** [entries] as a fresh `instruments:` section. */
    fun render(
        entries: List<VenueInstrumentSpec>,
        source: String,
    ): String = header(source) + entries.sortedBy { it.meta.qktSymbol }.joinToString("") { it.entryText() }

    private fun header(source: String): String =
        "# Venue contract specs. Pulled entries come from: $source\n" +
            "# A commented cost field was not reported by the venue; set it by hand.\n" +
            "instruments:\n"

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
        if (out ==
            null
        ) {
            print(render(pulled, source = configPath.toString()))
        } else {
            write(out, pulled, configPath.toString())
        }
        if (out != null) println("qkt: wrote ${pulled.size} instrument spec(s) to $out")
        for (spec in pulled) {
            for ((cost, reason) in spec.unreported) {
                System.err.println(
                    "qkt: ${spec.meta.qktSymbol}: ${cost.name.lowercase()} not reported by the venue ($reason)",
                )
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun usage(message: String): Int {
        System.err.println("qkt: instruments pull: $message")
        return ExitCodes.ARG_ERROR
    }
}
