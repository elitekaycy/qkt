package com.qkt.cli.bot

import com.qkt.cli.Args
import com.qkt.cli.BacktestContext
import com.qkt.cli.CliDataRoot
import com.qkt.cli.ExitCodes
import com.qkt.cli.SymbolSetResolution
import com.qkt.common.SystemClock
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.CatalogValue
import java.nio.file.Path

/**
 * `qkt bot universe "<set spec>" [--instruments <yaml>] [--as-of <date>] [--json]`: the members a symbol set
 * resolves to, from the same parser and resolver a strategy's `SYMBOLS` uses, so an agent's scan and the
 * strategy's members agree (design §3.10). The catalog is the instruments file a backtest would read; `--as-of`
 * dates `dte` (default: now). A picked list is checked against the catalog, name by name.
 */
class BotUniverseCommand(
    private val args: Args,
) {
    fun run(): Int {
        val json = args.flag("json")
        return botRun(json) {
            try {
                execute(json)
            } catch (e: BacktestContext.Companion.SetupError) {
                botFail(json, e.message ?: "cannot resolve the set")
            }
        }
    }

    private fun execute(json: Boolean): Int {
        val text = args.requirePositional(0, "symbol set, e.g. \"EXNESS:* WHERE kind = spot TOP 20 BY contract_size\"")
        val spec = SymbolSetSpec.parse(text)
        val dataRoot = args.option("data-root")?.let(Path::of) ?: CliDataRoot.resolve(args).path
        val catalog = SymbolSetResolution.catalog(args, dataRoot)
        val nowMs = args.option("as-of")?.let { BacktestContext.parseInstant(it).toEpochMilli() } ?: SystemClock().now()
        val members: List<InstrumentMeta> =
            when (spec) {
                is SymbolSetSpec.Union ->
                    error("a union names sets declared in a strategy; resolve each set's own spec")
                is SymbolSetSpec.Picked ->
                    spec.names.map { name ->
                        catalog.firstOrNull { it.qktSymbol.equals("${spec.venue}:$name", ignoreCase = true) }
                            ?: error("'${spec.venue}:$name' is not in the instruments file")
                    }
                is SymbolSetSpec.Filtered -> {
                    SymbolSetResolution
                        .members("universe", spec, catalog, nowMs, survivorshipAccepted = true)
                        .map { name -> catalog.first { it.qktSymbol.equals("${spec.venue}:$name", ignoreCase = true) } }
                }
            }
        if (json) {
            val head = jsonObj("ok" to true, "spec" to spec.canonical, "asOf" to nowMs, "count" to members.size)
            println(head.dropLast(1) + ",\"members\":" + jsonArr(members.map { row(it, nowMs) }) + "}")
        } else {
            println("${spec.canonical}: ${members.size} member(s)")
            for (meta in members) {
                val facts =
                    CatalogField.entries.drop(1).mapNotNull { f ->
                        f.read(meta, nowMs)?.let { "${f.token}=${plain(it)}" }
                    }
                println("  ${meta.qktSymbol}  ${facts.joinToString("  ")}")
            }
        }
        return ExitCodes.SUCCESS
    }

    private fun row(
        meta: InstrumentMeta,
        nowMs: Long,
    ): String =
        jsonObj(
            *(
                listOf("qktSymbol" to meta.qktSymbol) +
                    CatalogField.entries.map { f ->
                        f.token to
                            when (val v = f.read(meta, nowMs)) {
                                null -> null
                                is CatalogValue.Num -> v.value
                                is CatalogValue.Str -> v.value
                            }
                    }
            ).toTypedArray(),
        )

    private fun plain(v: CatalogValue): String =
        if (v is CatalogValue.Num) v.value.toPlainString() else (v as CatalogValue.Str).value
}
