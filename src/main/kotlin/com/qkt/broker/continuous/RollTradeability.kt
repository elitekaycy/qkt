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
