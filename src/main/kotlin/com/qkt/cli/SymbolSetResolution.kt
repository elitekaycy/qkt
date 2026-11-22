package com.qkt.cli

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetExpansion
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.UniverseResolver
import com.qkt.instrument.universe.VenueCatalog
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a run's filtered symbol sets once, at start (design §3.2, step 2): every set becomes its members as of the
 * start instant, and the run then declares plain streams. A backtest reads its [SetCatalog] from the instruments
 * file ([FileCatalog]), a live session from the venue's listing ([LiveSetCatalog]); both rank their rows with the one
 * [UniverseResolver], so the same instruments always give the same members. A picked list never reaches here; the
 * parser expands it. A set over equities from the instruments file is refused unless `--survivorship-accepted` is
 * passed, because a flat file has no listing history, and the acceptance is printed with the run.
 */
internal object SymbolSetResolution {
    const val SURVIVORSHIP_FLAG: String = "survivorship-accepted"

    /** The instruments file's [entries] as a [Catalog]: the rows whose broker prefix is the set's venue. */
    class FileCatalog(
        private val entries: List<InstrumentMeta>,
    ) : SetCatalog {
        override val source: String = "the instruments file"
        override val survivorsOnly: Boolean = true

        override fun venue(
            alias: String,
            spec: SymbolSetSpec.Filtered,
        ): VenueCatalog =
            VenueCatalog(entries.filter { it.qktSymbol.substringBefore(':').equals(spec.venue, ignoreCase = true) })
    }

    /** The catalog [args] names: `--instruments`, else `instruments.yaml` under [dataRoot]; absent is a setup error. */
    fun catalog(
        args: Args,
        dataRoot: Path,
    ): List<InstrumentMeta> {
        val path = args.option("instruments")?.let(Path::of) ?: dataRoot.resolve("instruments.yaml")
        if (!Files.exists(path)) {
            throw BacktestContext.Companion.SetupError(
                "a filtered symbol set needs a catalog, and there is no instruments file at $path " +
                    "(pass --instruments <file>)",
            )
        }
        return YamlInstrumentRegistry.load(path).all()
    }

    /**
     * [ast] with every filtered set replaced by its members from [catalog] as of [nowMs]; unchanged without sets. With
     * [keepDynamic] a set whose members move (`DYNAMIC`, or reading market fields) and its loops stay in place for
     * [DynamicSetRuns]; otherwise one is refused.
     */
    fun resolve(
        ast: StrategyAst,
        catalog: SetCatalog,
        nowMs: Long,
        survivorshipAccepted: Boolean,
        note: (String) -> Unit = { System.err.println("qkt: note: $it") },
        keepDynamic: Boolean = false,
    ): StrategyAst {
        if (ast.streams.none { it.broker == SET_BROKER }) return ast
        val resolver =
            object : SymbolSetExpansion.Resolver {
                override fun members(
                    alias: String,
                    spec: SymbolSetSpec,
                ): List<String>? {
                    val filtered = spec as SymbolSetSpec.Filtered
                    if (keepDynamic && filtered.moves) return null
                    val members = members(alias, filtered, catalog, nowMs, survivorshipAccepted)
                    note("symbol set '$alias': ${members.size} member(s) resolved at start: ${members.joinToString()}")
                    return members
                }
            }
        val expanded = SymbolSetExpansion.expand(ast.streams, ast.rules, resolver)
        return ast.copy(streams = expanded.streams, rules = expanded.rules)
    }

    /** The bare symbols `VENUE:* WHERE ... TOP n` names in [catalog] as of [nowMs], or why it cannot run. */
    fun members(
        alias: String,
        spec: SymbolSetSpec.Filtered,
        catalog: SetCatalog,
        nowMs: Long,
        survivorshipAccepted: Boolean,
    ): List<String> {
        if (spec.moves) {
            val why =
                when {
                    spec.dynamic -> "is DYNAMIC"
                    spec.readsMarket -> "reads as-of market fields (close, volume, spread)"
                    else -> "follows event families (${spec.slot})"
                }
            val fix = if (spec.dynamic) "; drop DYNAMIC to resolve its members once, at start" else ""
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias' $why: its members join and leave during a run, so it has no one member list$fix",
            )
        }
        val venue = rows(alias, spec, catalog, survivorshipAccepted)
        val members =
            UniverseResolver.members(venue.rows, spec.venue, spec.filter, spec.top, spec.by, spec.descending, nowMs)
        if (members.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': no ${spec.venue} instrument passes ${spec.filter.canonical}${omitted(venue)}",
            )
        }
        return members.map { it.qktSymbol.substringAfter(':') }
    }

    /** The catalog rows of [spec]'s venue a set ranks, or why it cannot run: over the member cap, none, survivors. */
    fun rows(
        alias: String,
        spec: SymbolSetSpec.Filtered,
        catalog: SetCatalog,
        survivorshipAccepted: Boolean,
    ): VenueCatalog {
        if (spec.top > UniverseResolver.DEFAULT_MAX_MEMBERS) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': TOP ${spec.top} is above the member cap of " +
                    "${UniverseResolver.DEFAULT_MAX_MEMBERS}",
            )
        }
        val venue = catalog.venue(alias, spec)
        if (venue.rows.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': ${catalog.source} lists no ${spec.venue}: symbol${omitted(venue)}",
            )
        }
        if (catalog.survivorsOnly && !survivorshipAccepted && venue.rows.any { CatalogField.kindOf(it) == "equity" }) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias' filters ${spec.venue} equities from an instruments file with no listing " +
                    "history, so a backtest would only ever see today's survivors; pass --$SURVIVORSHIP_FLAG " +
                    "to run anyway (the run notes it)",
            )
        }
        return venue
    }

    /** What [venue]'s listing left out, as a parenthesised note, or nothing. */
    fun omitted(venue: VenueCatalog): String = venue.omitted?.let { " ($it)" }.orEmpty()
}
