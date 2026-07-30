package com.qkt.risk

import java.math.BigDecimal

/**
 * Optional per-strategy risk limits shared by live and replay wiring.
 *
 * A null field disables that limit. Percentages are fractions in `(0, 1]`, quantities
 * and monetary limits must be positive, and counts must be greater than zero.
 */
data class StrategyRiskLimits(
    val maxDailyLoss: BigDecimal? = null,
    val maxPositionSize: BigDecimal? = null,
    val maxOpenPositions: Int? = null,
    val maxDrawdownPct: BigDecimal? = null,
    val maxDailyDrawdownPct: BigDecimal? = null,
    val maxTradesPerDay: Int? = null,
    val cooldownAfterLossMs: Long? = null,
    val cooldownAfterLossAfterConsecutive: Int = 1,
    val lossStreakHalt: Int? = null,
    val lossStreakHaltScope: HaltScope = HaltScope.PERSISTENT,
) {
    init {
        requirePositive(maxDailyLoss, "maxDailyLoss")
        requirePositive(maxPositionSize, "maxPositionSize")
        requirePositive(maxOpenPositions, "maxOpenPositions")
        requireFraction(maxDrawdownPct, "maxDrawdownPct")
        requireFraction(maxDailyDrawdownPct, "maxDailyDrawdownPct")
        requirePositive(maxTradesPerDay, "maxTradesPerDay")
        requirePositive(cooldownAfterLossMs, "cooldownAfterLossMs")
        require(cooldownAfterLossAfterConsecutive > 0) {
            "StrategyRiskLimits.cooldownAfterLossAfterConsecutive must be > 0: " +
                cooldownAfterLossAfterConsecutive
        }
        requirePositive(lossStreakHalt, "lossStreakHalt")
    }

    private fun requirePositive(
        value: BigDecimal?,
        name: String,
    ) {
        if (value != null) {
            require(value.signum() > 0) { "StrategyRiskLimits.$name must be > 0: $value" }
        }
    }

    private fun requirePositive(
        value: Int?,
        name: String,
    ) {
        if (value != null) {
            require(value > 0) { "StrategyRiskLimits.$name must be > 0: $value" }
        }
    }

    private fun requirePositive(
        value: Long?,
        name: String,
    ) {
        if (value != null) {
            require(value > 0) { "StrategyRiskLimits.$name must be > 0: $value" }
        }
    }

    private fun requireFraction(
        value: BigDecimal?,
        name: String,
    ) {
        if (value != null) {
            require(value.signum() > 0 && value <= BigDecimal.ONE) {
                "StrategyRiskLimits.$name must be in (0, 1]: $value"
            }
        }
    }
}
