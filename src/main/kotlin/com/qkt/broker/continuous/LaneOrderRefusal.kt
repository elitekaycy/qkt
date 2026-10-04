package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.instrument.PriceAdjustment
import java.time.Instant

/**
 * Why this stream refuses an order at [now], or null when it takes one: it must be adjusted by
 * panama, served and on a contract at [now], and not [rolling] to its next contract.
 */
internal fun ContinuousChain.orderRefusal(
    now: Long,
    rolling: Boolean,
): String? {
    val stream = symbol
    if (adjust != PriceAdjustment.PANAMA) {
        return "continuous futures orders need adjust: panama; $stream uses ${adjust.name.lowercase()}"
    }
    if (now < servedFromMs) return "$stream is served from ${Instant.ofEpochMilli(servedFromMs)}"
    if (indexAt(now) == null) return "$stream has no contract at ${Instant.ofEpochMilli(now)}"
    if (rolling) return "$stream is rolling to its next contract; resend once the roll is done"
    return null
}
