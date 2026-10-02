package com.qkt.instrument

import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * The two contracts' reference prices at one roll instant: [fromPrice] of the contract being left,
 * [toPrice] of the contract being entered, as exact decimal strings.
 */
@Serializable
data class RollRecord(
    val atMs: Long,
    val from: String,
    val to: String,
    val fromPrice: String,
    val toPrice: String,
) {
    init {
        require(fromPrice.toBigDecimalOrNull()?.signum() == 1) { "RollRecord.fromPrice must be > 0: $fromPrice" }
        require(toPrice.toBigDecimalOrNull()?.signum() == 1) { "RollRecord.toPrice must be > 0: $toPrice" }
    }

    /** [fromPrice] as a number. */
    fun fromPriceValue(): BigDecimal = BigDecimal(fromPrice)

    /** [toPrice] as a number. */
    fun toPriceValue(): BigDecimal = BigDecimal(toPrice)
}

/**
 * Every measured roll of one root under one policy ([policy] is [RollPolicy.key]). Built once from
 * stored bars by `qkt fetch <ROOT> --rolls`; a live session appends the gaps it measures, so a later
 * backtest of the same window reuses them exactly.
 */
@Serializable
data class RollHistory(
    val root: String,
    val policy: String,
    val rolls: List<RollRecord>,
) {
    /** The record for the roll at [atMs] from contract [from] to [to], or null. */
    fun find(
        atMs: Long,
        from: String,
        to: String,
    ): RollRecord? = rolls.firstOrNull { it.atMs == atMs && it.from == from && it.to == to }
}
