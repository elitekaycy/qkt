package com.qkt.cli

import com.qkt.backtest.BrokerKind
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.instrument.LayeredInstrumentRegistry
import com.qkt.instrument.StandardInstrumentRegistry
import com.qkt.instrument.YamlInstrumentRegistry
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Loads a `--scenarios` YAML file: a list of fan-out scenarios that share one decoded feed. Each entry
 * is a map; only `label` is required, the rest fall back to the swept run's defaults. e.g.
 *
 * ```yaml
 * - label: rf-fixed-0.01-10000
 *   params: { slpct: "0.01", rrmult: "2" }   # override LET params, like --param
 *   strategy: /run/variant.qkt               # optional; default = the swept strategy
 *   broker: mt5-sim                           # optional; paper | mt5-sim
 *   instruments: /run/instruments.1.5x.yaml   # optional; layered over the standard registry
 *   startingBalance: "10000"                  # optional
 * ```
 *
 * Scenarios must not change the symbol set or timeframes — that is enforced per engine in
 * [BacktestContext.backtest], since the single decoded feed is keyed to the symbols.
 */
object ScenarioFile {
    fun load(path: Path): List<ScenarioSpec> {
        check(Files.exists(path)) { "scenarios file not found at $path" }
        val root = Load(LoadSettings.builder().build()).loadFromString(Files.readString(path))
        check(root is List<*>) { "scenarios file: top-level must be a list (got ${root?.let { it::class }})" }
        check(root.isNotEmpty()) { "scenarios file: list is empty" }
        return root.mapIndexed { i, raw ->
            check(raw is Map<*, *>) { "scenarios file: entry $i must be a map" }
            parse(raw, i)
        }
    }

    private fun parse(
        entry: Map<*, *>,
        index: Int,
    ): ScenarioSpec {
        val label = entry["label"]?.toString() ?: error("scenarios file: entry $index missing 'label'")
        val params =
            (entry["params"] as? Map<*, *>)
                ?.entries
                ?.associate { (k, v) -> k.toString() to v.toString() }
                ?: emptyMap()
        val ast =
            (entry["strategy"] as? String)?.let { sp ->
                when (val parsed = Dsl.parseFile(Paths.get(sp))) {
                    is ParseResult.Success -> parsed.value
                    is ParseResult.Failure -> error("scenarios file entry $index: failed to parse strategy $sp")
                }
            }
        val broker =
            (entry["broker"] as? String)?.let {
                when (it) {
                    "paper" -> BrokerKind.PAPER
                    "mt5-sim" -> BrokerKind.MT5_SIM
                    else -> error("scenarios file entry $index: unknown broker '$it' (valid: paper, mt5-sim)")
                }
            }
        val instruments =
            (entry["instruments"] as? String)?.let {
                LayeredInstrumentRegistry(
                    listOf(YamlInstrumentRegistry.load(Paths.get(it)), StandardInstrumentRegistry),
                )
            }
        val balance = entry["startingBalance"]?.let { BigDecimal(it.toString()) }
        return ScenarioSpec(
            label = label,
            params = params,
            ast = ast,
            brokerKind = broker,
            instruments = instruments,
            startingBalance = balance,
        )
    }
}
