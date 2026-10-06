package com.qkt.instrument

import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset

/**
 * When a continuous series leaves a contract: [daysBeforeExpiry] calendar days before the expiry
 * date, at [atUtc]. Depends only on the contract's expiry, so backtest and live roll at the same
 * instant. [adjust] says how the series joins the next contract, and [anchor] which contract (its
 * code without the venue, e.g. `CLN20`) keeps raw prices; null anchors at the first measured one.
 */
data class RollPolicy(
    val daysBeforeExpiry: Int,
    val atUtc: LocalTime,
    val adjust: PriceAdjustment,
    val anchor: String? = null,
) {
    init {
        require(daysBeforeExpiry >= 0) { "RollPolicy.daysBeforeExpiry must be >= 0: $daysBeforeExpiry" }
        require(anchor == null || anchor.isNotBlank()) { "RollPolicy.anchor must not be blank" }
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
