package com.qkt.marketdata.openinterest

import java.math.BigDecimal

/**
 * One published open-interest figure of a contract, the same shape whatever the venue: [openInterest]
 * contracts outstanding, in the contract's order quantity (the unit a strategy's quantities are written in),
 * known from [timeMs] on. [timeMs] is when the venue made the figure known, never the start of the period it
 * describes, so a strategy reading it at [timeMs] could have read it live.
 */
data class OpenInterest(
    val timeMs: Long,
    val openInterest: BigDecimal,
) {
    init {
        require(openInterest.signum() >= 0) { "OpenInterest.openInterest must not be negative: $openInterest" }
    }
}

/** Where a contract's published open interest comes from: a gateway account, or a venue's public API. */
fun interface OpenInterestSource {
    /** The open interest of [qktSymbol] known from [fromMs] to [toMs], oldest first. */
    fun figures(
        qktSymbol: String,
        fromMs: Long,
        toMs: Long,
    ): List<OpenInterest>
}
