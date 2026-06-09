package com.qkt.cli

import java.math.BigDecimal

/**
 * Phase 25D: per-strategy risk caps loaded from `qkt.config.yaml`.
 *
 * Each field is optional — null means "no override, the daemon-global rule (if any)
 * still applies." `LiveSession.start` constructs rule instances from the populated
 * fields and attaches them to that strategy's evaluation path.
 *
 * YAML shape (under `risk.per_strategy.<strategy_name>`):
 * ```yaml
 * risk:
 *   max_daily_loss: "1000"     # global, applies to all
 *   per_strategy:
 *     ema_cross:
 *       max_daily_loss: "500"        # caps ema_cross at $500 even though global is $1000
 *       max_position_size: "1.0"     # rejects any order that would push |position| above 1.0
 *       max_open_positions: 3        # rejects new-symbol entries past 3 concurrent positions
 * ```
 */
data class PerStrategyRisk(
    val maxDailyLoss: BigDecimal? = null,
    val maxPositionSize: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxDrawdownPct: BigDecimal? = null,
    val maxDailyDrawdownPct: BigDecimal? = null,
) {
    init {
        if (maxDailyLoss != null) {
            require(maxDailyLoss.signum() > 0) { "PerStrategyRisk.maxDailyLoss must be > 0: $maxDailyLoss" }
        }
        if (maxPositionSize != null) {
            require(maxPositionSize.signum() > 0) { "PerStrategyRisk.maxPositionSize must be > 0: $maxPositionSize" }
        }
        if (maxOpenPositions != null) {
            require(maxOpenPositions > 0) { "PerStrategyRisk.maxOpenPositions must be > 0: $maxOpenPositions" }
        }
        if (maxDrawdownPct != null) {
            require(maxDrawdownPct.signum() > 0 && maxDrawdownPct <= BigDecimal.ONE) {
                "PerStrategyRisk.maxDrawdownPct must be in (0, 1]: $maxDrawdownPct"
            }
        }
        if (maxDailyDrawdownPct != null) {
            require(maxDailyDrawdownPct.signum() > 0 && maxDailyDrawdownPct <= BigDecimal.ONE) {
                "PerStrategyRisk.maxDailyDrawdownPct must be in (0, 1]: $maxDailyDrawdownPct"
            }
        }
    }
}
