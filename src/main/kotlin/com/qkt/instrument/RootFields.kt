package com.qkt.instrument

import java.math.BigDecimal

/**
 * Field parsing shared by the `futures:` and `options:` sections of `instruments.yaml`: every message
 * names the section [kind] and the root, so a typo fails the load with where it is.
 */
internal class RootFields(
    private val kind: String,
) {
    /** [raw] as a number, or a failure naming [key] of root [name]. */
    fun number(
        name: String,
        key: String,
        raw: String,
    ): BigDecimal = raw.toBigDecimalOrNull() ?: error("$kind root $name: '$key' must be a number, got '$raw'")

    /** [raw] as a whole number ≥ 0, or a failure naming [key] of root [name]. */
    fun wholeNumber(
        name: String,
        key: String,
        raw: String,
    ): Int =
        raw.toIntOrNull()?.takeIf { it >= 0 } ?: error("$kind root $name: $key must be a whole number >= 0, got '$raw'")

    /** A `margin: { initial, maintenance, basis }` map of root [name]. */
    fun margin(
        raw: Map<*, *>,
        name: String,
    ): MarginTerms {
        val unknown = raw.keys.map { it.toString() }.filter { it !in MARGIN_KEYS }
        require(unknown.isEmpty()) { "$kind root $name: unknown margin key(s) $unknown; allowed: $MARGIN_KEYS" }

        fun req(key: String): String = raw[key]?.toString() ?: error("$kind root $name: margin missing '$key'")
        val basis =
            when (req("basis").lowercase()) {
                "per_contract" -> MarginBasis.PER_CONTRACT
                "notional" -> MarginBasis.NOTIONAL
                else -> error("$kind root $name: margin basis must be per_contract or notional")
            }
        return MarginTerms(
            number(name, "margin.initial", req("initial")),
            number(name, "margin.maintenance", req("maintenance")),
            basis,
        )
    }

    companion object {
        private val MARGIN_KEYS = setOf("initial", "maintenance", "basis")

        /**
         * The top-level key in [keys] that looks like a mistyped [section] — a case or singular variant
         * (`future:`, `Options:`) — or null. Keys that merely start with the same letters are not judged.
         */
        fun misspelledSection(
            keys: Collection<Any?>,
            section: String,
        ): String? =
            keys.map { it.toString() }.firstOrNull {
                it != section &&
                    (it.equals(section, ignoreCase = true) || it.equals(section.removeSuffix("s"), ignoreCase = true))
            }
    }
}
