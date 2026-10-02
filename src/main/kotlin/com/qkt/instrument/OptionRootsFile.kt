package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Reads the `options:` section of an `instruments.yaml`. Keys are checked strictly, like `futures:`:
 * a typo in a contract size or tick must fail the load, not fall back to a default.
 */
object OptionRootsFile {
    private val REQUIRED =
        listOf("root", "currency", "contractSize", "tickSize", "volumeStep", "volumeMin", "underlyingIndex")
    private val OPTIONAL =
        listOf(
            "tickSteps",
            "exchangeFeePerContract",
            "takerFeeRate",
            "margin",
            "chains",
            "markSpread",
            "maxQuoteAgeMinutes",
            "feeCapRate",
            "deliveryFeeRate",
        )
    private val STEP_KEYS = setOf("above", "tick")
    private val fields = RootFields("options")

    /** Every root declared in [path], in file order; empty when the file has no `options:` section. */
    fun load(path: Path): List<OptionRoot> {
        val document = Load(LoadSettings.builder().build()).loadFromString(Files.readString(path))
        check(document is Map<*, *>) { "$path: top-level must be a map" }
        val nearMiss = RootFields.misspelledSection(document.keys, "options")
        require(nearMiss == null) { "$path: unknown section '$nearMiss'; did you mean 'options:'?" }
        val list = document["options"] ?: return emptyList()
        check(list is List<*>) { "$path: 'options' must be a list" }
        val roots =
            list.mapIndexed { i, raw ->
                parse(
                    raw as? Map<*, *> ?: error("$path: options entry $i must be a map"),
                    i,
                )
            }
        val duplicate = roots.groupBy { it.root }.entries.firstOrNull { it.value.size > 1 }
        check(duplicate == null) { "$path: duplicate options root '${duplicate?.key}'" }
        return roots
    }

    private fun parse(
        entry: Map<*, *>,
        index: Int,
    ): OptionRoot {
        val name = entry["root"]?.toString() ?: "options entry $index"
        val unknown = entry.keys.map { it.toString() }.filter { it !in REQUIRED && it !in OPTIONAL }
        require(unknown.isEmpty()) { "options root $name: unknown key(s) $unknown; allowed: ${REQUIRED + OPTIONAL}" }

        fun req(key: String): String =
            entry[key]?.toString() ?: error("options root $name: missing required key '$key'")

        fun num(key: String): BigDecimal = fields.number(name, key, req(key))

        fun opt(key: String): BigDecimal? = entry[key]?.let { fields.number(name, key, it.toString()) }
        try {
            return OptionRoot(
                root = req("root"),
                currency = req("currency"),
                contractSize = num("contractSize"),
                tickSteps = TickSteps(num("tickSize"), steps(entry["tickSteps"], name)),
                volumeStep = num("volumeStep"),
                volumeMin = num("volumeMin"),
                underlyingIndex = req("underlyingIndex"),
                exchangeFeePerContract = opt("exchangeFeePerContract") ?: BigDecimal.ZERO,
                takerFeeRate = opt("takerFeeRate") ?: BigDecimal.ZERO,
                margin =
                    entry["margin"]?.let {
                        fields.margin(
                            it as? Map<*, *> ?: error("options root $name: margin must be a map"),
                            name,
                        )
                    },
                chains = entry["chains"]?.let { chains(it.toString(), name) },
                markSpread = opt("markSpread"),
                maxQuoteAgeMinutes =
                    entry["maxQuoteAgeMinutes"]?.let { fields.wholeNumber(name, "maxQuoteAgeMinutes", it.toString()) }
                        ?: 60,
                feeCapRate = opt("feeCapRate"),
                deliveryFeeRate = opt("deliveryFeeRate") ?: BigDecimal.ZERO,
            )
        } catch (e: IllegalArgumentException) {
            if (e.message?.startsWith("options root") == true) throw e
            throw IllegalArgumentException("options root $name: ${e.message}", e)
        }
    }

    private fun chains(
        value: String,
        name: String,
    ): QuoteSource =
        when (value) {
            "trade" -> QuoteSource.TRADE
            "book" -> QuoteSource.BOOK
            else -> throw IllegalArgumentException("options root $name: chains must be trade or book, got '$value'")
        }

    private fun steps(
        raw: Any?,
        name: String,
    ): List<TickStep> {
        if (raw == null) return emptyList()
        val list = raw as? List<*> ?: error("options root $name: tickSteps must be a list of { above, tick }")
        return list.map { item ->
            val step = item as? Map<*, *> ?: error("options root $name: each tickSteps entry must be a map")
            val unknown = step.keys.map { it.toString() }.filter { it !in STEP_KEYS }
            require(unknown.isEmpty()) { "options root $name: unknown tickSteps key(s) $unknown; allowed: $STEP_KEYS" }

            fun value(key: String) =
                fields.number(
                    name,
                    "tickSteps.$key",
                    step[key]?.toString() ?: error("options root $name: tickSteps missing '$key'"),
                )
            TickStep(value("above"), value("tick"))
        }
    }
}
