package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * [InstrumentRegistry] backed by a YAML manifest. Used by backtest where no live broker
 * exists to query `/symbol_info`.
 *
 * Schema — `instruments` list, one entry per qkt-side symbol:
 *
 * ```yaml
 * instruments:
 *   - qktSymbol: EXNESS:XAUUSD
 *     contractSize: 100
 *     volumeStep: 0.01
 *     volumeMin: 0.01
 *     volumeMax: 200       # optional
 *     pointSize: 0.001
 *     digits: 3
 *     tradeStopsLevelPoints: 0
 *     commissionPerLot: 3.50   # optional, default 0 — backtest cost per lot per side
 *     slippagePoints: 5        # optional, default 0 — adverse execution slip in points (mt5-sim)
 * ```
 *
 * Duplicate `qktSymbol` entries fail loudly at [load] — fail-fast keeps a YAML edit
 * from silently routing a strategy to the wrong meta.
 */
class YamlInstrumentRegistry private constructor(
    private val table: Map<String, InstrumentMeta>,
) : InstrumentRegistry {
    override fun lookup(qktSymbol: String): InstrumentMeta? = table[qktSymbol]

    companion object {
        fun load(path: Path): YamlInstrumentRegistry {
            check(Files.exists(path)) { "instruments.yaml not found at $path" }
            val text = Files.readString(path)
            val root = Load(LoadSettings.builder().build()).loadFromString(text)
            check(root is Map<*, *>) { "instruments.yaml: top-level must be a map (got ${root?.let { it::class }})" }
            val list =
                root["instruments"] as? List<*>
                    ?: error("instruments.yaml: missing 'instruments' list")
            val table = mutableMapOf<String, InstrumentMeta>()
            for ((i, raw) in list.withIndex()) {
                check(raw is Map<*, *>) { "instruments.yaml: entry $i must be a map" }
                val meta = parseEntry(raw, i)
                check(meta.qktSymbol !in table) {
                    "instruments.yaml: duplicate qktSymbol '${meta.qktSymbol}'"
                }
                table[meta.qktSymbol] = meta
            }
            return YamlInstrumentRegistry(table)
        }

        private fun parseEntry(
            entry: Map<*, *>,
            index: Int,
        ): InstrumentMeta {
            fun req(key: String): Any =
                entry[key] ?: error("instruments.yaml: entry $index missing required field '$key'")

            fun bd(key: String): BigDecimal = BigDecimal(req(key).toString())

            fun bdOpt(key: String): BigDecimal? = entry[key]?.let { BigDecimal(it.toString()) }

            fun int(key: String): Int = (req(key) as Number).toInt()

            fun intOpt(key: String): Int? = (entry[key] as? Number)?.toInt()

            return InstrumentMeta(
                qktSymbol = req("qktSymbol").toString(),
                contractSize = bd("contractSize"),
                volumeStep = bd("volumeStep"),
                volumeMin = bd("volumeMin"),
                volumeMax = bdOpt("volumeMax"),
                pointSize = bd("pointSize"),
                digits = int("digits"),
                tradeStopsLevelPoints = int("tradeStopsLevelPoints"),
                commissionPerLot = bdOpt("commissionPerLot") ?: BigDecimal.ZERO,
                slippagePoints = intOpt("slippagePoints") ?: 0,
            )
        }
    }
}
