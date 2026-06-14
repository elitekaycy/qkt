package com.qkt.dsl.stdlib

import java.math.BigDecimal

object Constants {
    val HALF_PERCENT: BigDecimal = BigDecimal("0.005")
    val ONE_PERCENT: BigDecimal = BigDecimal("0.01")
    val TWO_PERCENT: BigDecimal = BigDecimal("0.02")
    val THREE_PERCENT: BigDecimal = BigDecimal("0.03")
    val FIVE_PERCENT: BigDecimal = BigDecimal("0.05")
    val TEN_PERCENT: BigDecimal = BigDecimal("0.10")
    val QUARTER_PERCENT: BigDecimal = BigDecimal("0.0025")
    val BPS: BigDecimal = BigDecimal("0.0001")

    private val table: Map<String, BigDecimal> =
        mapOf(
            "HALF_PERCENT" to HALF_PERCENT,
            "ONE_PERCENT" to ONE_PERCENT,
            "TWO_PERCENT" to TWO_PERCENT,
            "THREE_PERCENT" to THREE_PERCENT,
            "FIVE_PERCENT" to FIVE_PERCENT,
            "TEN_PERCENT" to TEN_PERCENT,
            "QUARTER_PERCENT" to QUARTER_PERCENT,
            "BPS" to BPS,
        )

    fun byName(name: String): BigDecimal? = table[name]

    /** Every registered constant name, for editor tooling (completion, hover). */
    fun names(): Set<String> = table.keys
}
