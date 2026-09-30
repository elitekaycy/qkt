package com.qkt.instrument

import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalTime
import java.time.format.DateTimeParseException
import org.snakeyaml.engine.v2.api.Load
import org.snakeyaml.engine.v2.api.LoadSettings

/**
 * Reads the `futures:` section of an `instruments.yaml`. Keys are checked strictly — a typo in a
 * margin or multiplier must fail the load, not silently fall back to a default.
 */
object FuturesRootsFile {
    private val REQUIRED = listOf("root", "currency", "multiplier", "tickSize", "volumeStep", "volumeMin")
    private val OPTIONAL = listOf("volumeMax", "calendar", "exchangeFeePerContract", "takerFeeRate", "margin", "roll")
    private val MARGIN_KEYS = setOf("initial", "maintenance", "basis")
    private val ROLL_KEYS = setOf("daysBeforeExpiry", "atUtc", "adjust")

    /** Every root declared in [path], in file order; empty when the file has no `futures:` section. */
    fun load(path: Path): List<FuturesRoot> {
        val root = Load(LoadSettings.builder().build()).loadFromString(Files.readString(path))
        check(root is Map<*, *>) { "$path: top-level must be a map" }
        val nearMiss =
            root.keys.map { it.toString() }.firstOrNull {
                it != "futures" &&
                    it.lowercase().startsWith("future")
            }
        require(nearMiss == null) { "$path: unknown section '$nearMiss'; did you mean 'futures:'?" }
        val list = root["futures"] ?: return emptyList()
        check(list is List<*>) { "$path: 'futures' must be a list" }
        val roots =
            list.mapIndexed { i, raw ->
                parse(
                    raw as? Map<*, *> ?: error("$path: futures entry $i must be a map"),
                    i,
                )
            }
        val duplicate = roots.groupBy { it.root }.entries.firstOrNull { it.value.size > 1 }
        check(duplicate == null) { "$path: duplicate futures root '${duplicate?.key}'" }
        return roots
    }

    private fun parse(
        entry: Map<*, *>,
        index: Int,
    ): FuturesRoot {
        val name = entry["root"]?.toString() ?: "futures entry $index"
        val unknown = entry.keys.map { it.toString() }.filter { it !in REQUIRED && it !in OPTIONAL }
        require(unknown.isEmpty()) { "futures root $name: unknown key(s) $unknown; allowed: ${REQUIRED + OPTIONAL}" }

        fun req(key: String): String =
            entry[key]?.toString() ?: error("futures root $name: missing required key '$key'")

        fun num(key: String): BigDecimal = number(name, key, req(key))

        fun opt(key: String): BigDecimal? = entry[key]?.let { number(name, key, it.toString()) }
        return FuturesRoot(
            root = req("root"),
            currency = req("currency"),
            multiplier = num("multiplier"),
            tickSize = num("tickSize"),
            volumeStep = num("volumeStep"),
            volumeMin = num("volumeMin"),
            volumeMax = opt("volumeMax"),
            calendar = entry["calendar"]?.toString(),
            exchangeFeePerContract = opt("exchangeFeePerContract") ?: BigDecimal.ZERO,
            takerFeeRate = opt("takerFeeRate") ?: BigDecimal.ZERO,
            margin =
                entry["margin"]?.let {
                    margin(
                        it as? Map<*, *> ?: error("futures root $name: margin must be a map"),
                        name,
                    )
                },
            roll =
                entry["roll"]?.let {
                    roll(
                        it as? Map<*, *> ?: error("futures root $name: roll must be a map"),
                        name,
                    )
                },
        ).also { validate(it, name) }
    }

    private fun margin(
        raw: Map<*, *>,
        name: String,
    ): MarginTerms {
        val unknown = raw.keys.map { it.toString() }.filter { it !in MARGIN_KEYS }
        require(unknown.isEmpty()) { "futures root $name: unknown margin key(s) $unknown; allowed: $MARGIN_KEYS" }

        fun req(key: String): String = raw[key]?.toString() ?: error("futures root $name: margin missing '$key'")
        val basis =
            when (req("basis").lowercase()) {
                "per_contract" -> MarginBasis.PER_CONTRACT
                "notional" -> MarginBasis.NOTIONAL
                else -> error("futures root $name: margin basis must be per_contract or notional")
            }
        return MarginTerms(
            number(name, "margin.initial", req("initial")),
            number(name, "margin.maintenance", req("maintenance")),
            basis,
        )
    }

    private fun number(
        name: String,
        key: String,
        raw: String,
    ): BigDecimal = raw.toBigDecimalOrNull() ?: error("futures root $name: '$key' must be a number, got '$raw'")

    private fun roll(
        raw: Map<*, *>,
        name: String,
    ): RollPolicy {
        val unknown = raw.keys.map { it.toString() }.filter { it !in ROLL_KEYS }
        require(unknown.isEmpty()) { "futures root $name: unknown roll key(s) $unknown; allowed: $ROLL_KEYS" }

        fun req(key: String): String = raw[key]?.toString() ?: error("futures root $name: roll missing '$key'")
        val days =
            req("daysBeforeExpiry").toIntOrNull()
                ?: error("futures root $name: roll.daysBeforeExpiry must be a whole number")
        val at =
            try {
                LocalTime.parse(req("atUtc"))
            } catch (e: DateTimeParseException) {
                throw IllegalArgumentException(
                    "futures root $name: roll.atUtc must be a UTC time like 08:00, got '${req("atUtc")}'",
                    e,
                )
            }
        val adjust =
            PriceAdjustment.entries.firstOrNull { it.name.equals(req("adjust"), ignoreCase = true) }
                ?: error(
                    "futures root $name: roll.adjust must be one of ${PriceAdjustment.entries.map {
                        it.name.lowercase()
                    }}",
                )
        require(days >= 0) { "futures root $name: roll.daysBeforeExpiry must be >= 0, got $days" }
        return RollPolicy(days, at, adjust)
    }

    private fun validate(
        root: FuturesRoot,
        name: String,
    ) {
        try {
            root.metaFor(root.root, expiryMs = null)
        } catch (e: IllegalArgumentException) {
            throw IllegalArgumentException("futures root $name: ${e.message}", e)
        }
    }
}
