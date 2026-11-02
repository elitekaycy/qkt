package com.qkt.app

import java.math.BigDecimal

/**
 * What one session reports toward the daemon's `risk.hard_daily_loss`, read on its engine thread.
 * [open] sums the open P&L of the symbols with a fresh, valid mark; every other symbol holding a position is
 * in [unmeasured] with why, and counts as nothing. Example: `open = -40`, `unmeasured = {"X:GOLD": "stale quote"}`.
 */
data class DailyLimitReading(
    /** The key this session's realized P&L has in the daemon's total. */
    val sessionId: String,
    val realizedToday: BigDecimal,
    val open: BigDecimal,
    val unmeasured: Map<String, String>,
    /** Legs the session's ledger still holds. */
    val openLegs: Int,
    /** Whether a close of one of those legs is still working. */
    val closing: Boolean,
)

/** How a session answers the daemon's hard daily limit. A handle with nothing to report answers null. */
interface DailyLimitReads {
    /** Blocks until the engine thread answers; throws when it does not, as any engine snapshot. */
    fun limitReading(): DailyLimitReading? = null
}
