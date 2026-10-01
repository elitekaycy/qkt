package com.qkt.strategy

import java.math.BigDecimal

/** Where a live option structure is in its life. */
enum class StructureState {
    /** Its opening group was accepted and some leg has not filled yet. */
    PENDING,

    /** Every leg filled. */
    OPEN,

    /** A leg failed after acceptance, and the filled legs are being closed. */
    UNWINDING,

    /** A `CLOSE` was sent for its held legs. */
    CLOSING,
}

/**
 * One leg of a live structure: [symbol] with [contractSize] and expiry [expiryMs]. [entryQuantity]
 * is the signed quantity filled when opening, at average [entryPrice] (null before any fill);
 * [heldQuantity] is what is still held, signed. [realized] is the premium P&L of what was closed or
 * settled, before fees.
 */
data class StructureLegPosition(
    val symbol: String,
    val contractSize: BigDecimal,
    val expiryMs: Long,
    val entryQuantity: BigDecimal,
    val entryPrice: BigDecimal?,
    val heldQuantity: BigDecimal,
    val realized: BigDecimal,
)

/** The live option structure [id] opened under [alias]: [size] contracts per leg, in [state]. */
data class StructurePosition(
    val id: String,
    val alias: String,
    val state: StructureState,
    val size: BigDecimal,
    val legs: List<StructureLegPosition>,
)

/**
 * A strategy's read-only view of its option structures. An alias holds at most one live structure,
 * from the moment its opening group is accepted until no leg is held and no leg order is working.
 */
interface StructureView {
    /** The live structure opened under [alias], or null. */
    fun live(alias: String): StructurePosition?

    /** Every live structure, oldest first. */
    fun all(): List<StructurePosition>

    /** The price [symbol] is marked at (the one equity uses), or null when it has none yet. */
    fun mark(symbol: String): BigDecimal?

    companion object {
        /** A view with no structures, for strategies run without the structure book. */
        val EMPTY: StructureView =
            object : StructureView {
                override fun live(alias: String): StructurePosition? = null

                override fun all(): List<StructurePosition> = emptyList()

                override fun mark(symbol: String): BigDecimal? = null
            }
    }
}
