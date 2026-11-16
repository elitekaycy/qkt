package com.qkt.cli

import com.qkt.dsl.ast.SET_BROKER
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.sets.SymbolSetExpansion
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.YamlInstrumentRegistry
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.UniverseResolver
import java.nio.file.Files
import java.nio.file.Path

/**
 * Resolves a backtest's filtered symbol sets once, at start, from the instruments file's `instruments:` list (design
 * §3.2, step 2): every set becomes its members as of `--from`, and the run then declares plain streams. A picked
 * list never reaches here; the parser expands it. A set over equities is refused unless `--survivorship-accepted`
 * is passed, because a flat instruments file has no listing history, and the acceptance is printed with the run.
 */
internal object SymbolSetResolution {
    const val SURVIVORSHIP_FLAG: String = "survivorship-accepted"

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

    /** [ast] with every filtered set replaced by its members from [catalog] as of [nowMs]; unchanged without sets. */
    fun resolve(
        ast: StrategyAst,
        catalog: List<InstrumentMeta>,
        nowMs: Long,
        survivorshipAccepted: Boolean,
        note: (String) -> Unit = { System.err.println("qkt: note: $it") },
    ): StrategyAst {
        if (ast.streams.none { it.broker == SET_BROKER }) return ast
        val resolver =
            object : SymbolSetExpansion.Resolver {
                override fun members(
                    alias: String,
                    spec: SymbolSetSpec,
                ): List<String> {
                    val members = members(alias, spec as SymbolSetSpec.Filtered, catalog, nowMs, survivorshipAccepted)
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
        catalog: List<InstrumentMeta>,
        nowMs: Long,
        survivorshipAccepted: Boolean,
    ): List<String> {
        if (spec.top > UniverseResolver.DEFAULT_MAX_MEMBERS) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': TOP ${spec.top} is above the member cap of " +
                    "${UniverseResolver.DEFAULT_MAX_MEMBERS}",
            )
        }
        val venueRows = catalog.filter { it.qktSymbol.substringBefore(':').equals(spec.venue, ignoreCase = true) }
        if (venueRows.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': the instruments file lists no ${spec.venue}: symbol",
            )
        }
        if (!survivorshipAccepted && venueRows.any { CatalogField.kindOf(it) == "equity" }) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias' filters ${spec.venue} equities from an instruments file with no listing " +
                    "history, so a backtest would only ever see today's survivors; pass --$SURVIVORSHIP_FLAG " +
                    "to run anyway (the run notes it)",
            )
        }
        val members =
            UniverseResolver.members(catalog, spec.venue, spec.filter, spec.top, spec.by, spec.descending, nowMs)
        if (members.isEmpty()) {
            throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': no ${spec.venue} instrument passes ${spec.filter.canonical}",
            )
        }
        return members.map { it.qktSymbol.substringAfter(':') }
    }
}
