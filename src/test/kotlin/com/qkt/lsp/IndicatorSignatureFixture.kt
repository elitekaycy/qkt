package com.qkt.lsp

/**
 * Turns a hover signature such as `macd(value, fast, slow, signal)` into a strategy that calls the
 * indicator exactly as documented, so a test can prove the documented form compiles. The leading
 * [seriesCount] parameters become series arguments (`s.close`, `s.candle`, `s.tick`, or a
 * condition); the rest become integer literals chosen to satisfy each indicator's argument rules.
 */
internal object IndicatorSignatureFixture {
    private val NUMERIC: Map<String, Int> =
        mapOf(
            "fast" to 12,
            "slow" to 26,
            "signal" to 9,
            "stddev" to 2,
            "atr_mult" to 2,
            "k" to 2,
            "k_period" to 14,
            "d_period" to 3,
            "anchorHour" to 8,
            "startHour" to 8,
            "endHour" to 16,
            "sessionStartHour" to 8,
            "startMinute" to 0,
            "endMinute" to 0,
            "ibMinutes" to 60,
            "bucketMinutes" to 30,
            "minGapHours" to 24,
            "nDays" to 5,
            "rangeLen" to 20,
            "reclaimBars" to 3,
            "armBars" to 5,
            "n" to 5,
        )

    /** The parameter names between the signature's parentheses, with the `…` ellipsis dropped. */
    fun parameters(signature: String): List<String> =
        signature
            .substringAfter('(')
            .substringBeforeLast(')')
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() && it != "…" }

    /** A minimal strategy whose one rule reads the indicator called as [signature] documents. */
    fun strategy(
        signature: String,
        seriesCount: Int,
    ): String {
        val name = signature.substringBefore('(')
        val args =
            parameters(signature).mapIndexed { i, param ->
                if (i < seriesCount) series(param) else (NUMERIC[param] ?: 20).toString()
            }
        return """
            STRATEGY t VERSION 1
            SYMBOLS
                s = X:Y EVERY 1m
            RULES
                WHEN $name(${args.joinToString(", ")}) > 0 THEN FLATTEN
            """.trimIndent()
    }

    private fun series(param: String): String =
        when {
            param.endsWith(".candle") -> "s.candle"
            param.endsWith(".tick") -> "s.tick"
            param == "condition" -> "s.close > 0"
            param == "stream" -> "s"
            else -> "s.close"
        }
}
