package com.qkt.instrument

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * When a continuous series leaves a contract: [daysBeforeExpiry] calendar days before the expiry
 * date, at [atUtc]. Depends only on the contract's expiry, so backtest and live roll at the same
 * instant. [adjust] says how the series joins the next contract.
 */
data class RollPolicy(
    val daysBeforeExpiry: Int,
    val atUtc: LocalTime,
    val adjust: PriceAdjustment,
) {
    init {
        require(daysBeforeExpiry >= 0) { "RollPolicy.daysBeforeExpiry must be >= 0: $daysBeforeExpiry" }
    }

    /** Identifies the roll instants this policy produces; histories built under another key do not apply. */
    val key: String get() = "${daysBeforeExpiry}d@$atUtc"

    /** The roll instant, UTC epoch millis, for a contract expiring at [expiryMs]. */
    fun rollAtMs(expiryMs: Long): Long {
        val expiryDate = Instant.ofEpochMilli(expiryMs).atZone(ZoneOffset.UTC).toLocalDate()
        val at =
            expiryDate
                .minusDays(daysBeforeExpiry.toLong())
                .atTime(atUtc)
                .toInstant(ZoneOffset.UTC)
                .toEpochMilli()
        require(
            at < expiryMs,
        ) { "roll at ${Instant.ofEpochMilli(at)} must be before expiry ${Instant.ofEpochMilli(expiryMs)}" }
        return at
    }
}
