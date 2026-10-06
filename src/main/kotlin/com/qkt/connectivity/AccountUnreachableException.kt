package com.qkt.connectivity

/**
 * [TradingAccount.verify] could not reach the venue, so the account's identity was never checked.
 *
 * Distinct from a mismatch (wrong login, server, live or demo, currency, leverage, position mode),
 * which is refused at once: an unreachable gateway may come back, so the daemon's boot preflight
 * retries this one until its deadline ([AccountPreflight]). Either way nothing trades unverified.
 */
class AccountUnreachableException(
    message: String,
    cause: Throwable? = null,
) : IllegalStateException(message, cause)
