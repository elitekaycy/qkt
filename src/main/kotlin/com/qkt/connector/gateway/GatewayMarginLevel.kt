package com.qkt.connector.gateway

import com.qkt.common.Clock
import java.math.BigDecimal
import java.math.MathContext

private val HUNDRED = BigDecimal(100)

/** The account's margin level as a percent (equity / used margin x 100), or null when it uses no margin. */
internal fun WireAccount.marginLevel(): BigDecimal? {
    val used = BigDecimal(marginUsed)
    return if (used.signum() > 0) BigDecimal(equity).divide(used, MathContext.DECIMAL64) * HUNDRED else null
}

/**
 * The margin level of a gateway account for the margin floor, from what `GET /v1/account` states, and cached for
 * [TTL_MS] as an MT5 account's is, since the floor asks on every entry. An account using no margin reads 0, as an
 * MT5 account does, which the floor approves: it has nothing at risk of a stop-out. A failed read is null, which
 * the floor counts as a missing reading.
 */
internal class GatewayMarginLevel(
    private val client: GatewayClient,
    private val clock: Clock,
) {
    private var cached: Pair<Long, BigDecimal?>? = null

    /** The level, at most [TTL_MS] old. */
    @Synchronized
    fun read(): BigDecimal? {
        val now = clock.now()
        cached?.let { (at, level) -> if (now - at < TTL_MS) return level }
        val level = runCatching { client.account().marginLevel() ?: BigDecimal.ZERO }.getOrNull()
        cached = now to level
        return level
    }

    private companion object {
        const val TTL_MS = 5_000L
    }
}
