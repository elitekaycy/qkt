package com.qkt.broker.mt5

import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Matches a venue-reported protection change against the values the engine itself last
 * requested, so the position poller only alarms on genuinely external changes (#1063).
 *
 * The venue quantizes SL/TP to the symbol's digits before reporting them back, while the
 * engine computes levels at full [com.qkt.common.Money] precision — a trailing push of
 * `4457.74345…` comes back as `4457.743`. Comparison therefore aligns the expected value
 * to the reported value's scale before testing equality; an actual foreign change still
 * differs at the venue's own scale and fails the match.
 */
internal object ProtectionExpectation {
    /** True when [expected] and [reported] agree once [expected] is quantized to the venue's scale. */
    fun matchesVenue(
        expected: BigDecimal?,
        reported: BigDecimal,
    ): Boolean {
        if (expected == null) return false
        if (expected.compareTo(reported) == 0) return true
        return expected.setScale(reported.scale(), RoundingMode.HALF_UP).compareTo(reported) == 0
    }
}
