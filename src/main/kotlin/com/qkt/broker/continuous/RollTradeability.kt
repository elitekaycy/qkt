package com.qkt.broker.continuous

import com.qkt.derivatives.futures.ContinuousChain
import java.time.Instant

/**
 * Why positions cannot be carried from contract [fromIndex] to [toIndex] by trading at [nowMs], or null
 * when they can: the stream skipped a whole contract, or the old contract expired before the stream
 * traded again (the exchange settles it).
 */
internal fun ContinuousChain.untradeableRoll(
    fromIndex: Int,
    toIndex: Int,
    nowMs: Long,
): String? {
    if (toIndex != fromIndex + 1) return "no data for ${toIndex - fromIndex - 1} contract(s) in between"
    val expiry = schedule.contracts[fromIndex].expiryMs
    if (nowMs < expiry) return null
    return "${contractSymbol(fromIndex)} expired at ${Instant.ofEpochMilli(expiry)} before the stream traded again"
}

/** Whether a retry at [nowMs] of a roll out of contract [fromIndex] would end well before that contract's expiry. */
internal fun ContinuousChain.clearForRetry(
    fromIndex: Int,
    nowMs: Long,
): Boolean = nowMs + RollLegIds.RETRY_EXPIRY_MARGIN_MS < schedule.contracts[fromIndex].expiryMs
