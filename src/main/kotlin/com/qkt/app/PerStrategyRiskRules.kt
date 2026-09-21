package com.qkt.app

import com.qkt.common.Clock
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltRule
import com.qkt.risk.HaltScope
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskRule
import java.math.BigDecimal

/** The per-strategy risk overrides a deploy may set; a null (or unset) limit adds no rule. */
internal class PerStrategyRiskLimits(
    val perStrategyMaxDailyLoss: BigDecimal?,
    val perStrategyMaxPositionSize: BigDecimal?,
    val perStrategyMaxOpenPositions: Int?,
    val perStrategyMaxDrawdownPct: BigDecimal?,
    val perStrategyMaxDailyDrawdownPct: BigDecimal?,
    val perStrategyMaxTradesPerDay: Int?,
    val perStrategyCooldownAfterLossMs: Long?,
    val perStrategyCooldownAfterLossAfterConsecutive: Int,
    val perStrategyLossStreakHalt: Int?,
    val perStrategyLossStreakHaltScope: HaltScope,
)

/**
 * Phase 25D: per-strategy risk overrides for the (single) strategy in a session.
 * The daemon creates one LiveSession per deployed strategy, so the first entry is
 * the only one. If the caller didn't set per-strategy caps, both lists stay empty, e.g.
 * `maxTradesPerDay = 3` alone yields one [com.qkt.risk.rules.MaxTradesPerDay] risk rule.
 */
internal class PerStrategyRiskRules(
    limits: PerStrategyRiskLimits,
    riskOwnerStrategyId: String?,
    strategyPositions: StrategyPositionTracker,
    startingBalances: Map<String, BigDecimal>,
    initialBalance: BigDecimal,
    totalDdBasis: DrawdownBasis,
    pacerLedger: PacerLedger,
    clock: Clock,
) {
    private val perStrategyHaltRules = mutableListOf<HaltRule>()
    private val perStrategyRiskRules = mutableListOf<RiskRule>()

    /** Halt rules scoped to the owning strategy, in evaluation order. */
    val haltRules: List<HaltRule> get() = perStrategyHaltRules

    /** Pre-trade rules scoped to the owning strategy, in evaluation order. */
    val riskRules: List<RiskRule> get() = perStrategyRiskRules

    init {
        with(limits) {
            if (riskOwnerStrategyId != null) {
                perStrategyMaxDailyLoss?.let {
                    perStrategyHaltRules.add(
                        com.qkt.risk.rules
                            .MaxStrategyDailyLoss(riskOwnerStrategyId, it),
                    )
                }
                perStrategyMaxPositionSize?.let {
                    perStrategyRiskRules.add(
                        com.qkt.risk.rules
                            .MaxStrategyPositionSize(riskOwnerStrategyId, it, strategyPositions),
                    )
                }
                perStrategyMaxOpenPositions?.let {
                    perStrategyRiskRules.add(
                        com.qkt.risk.rules
                            .MaxStrategyOpenPositions(riskOwnerStrategyId, it, strategyPositions),
                    )
                }
                val ownerInitialBalance = startingBalances[riskOwnerStrategyId] ?: initialBalance
                perStrategyMaxDrawdownPct?.let {
                    perStrategyHaltRules.add(
                        com.qkt.risk.rules
                            .MaxStrategyDrawdown(riskOwnerStrategyId, it, totalDdBasis, ownerInitialBalance),
                    )
                }
                perStrategyMaxDailyDrawdownPct?.let {
                    perStrategyHaltRules.add(
                        com.qkt.risk.rules
                            .MaxStrategyDailyDrawdown(riskOwnerStrategyId, it),
                    )
                }
                perStrategyMaxTradesPerDay?.let {
                    perStrategyRiskRules.add(
                        com.qkt.risk.rules
                            .MaxTradesPerDay(it, pacerLedger, clock, riskOwnerStrategyId),
                    )
                }
                perStrategyCooldownAfterLossMs?.let {
                    perStrategyRiskRules.add(
                        com.qkt.risk.rules
                            .CooldownAfterLoss(
                                durationMs = it,
                                ledger = pacerLedger,
                                clock = clock,
                                afterConsecutive = perStrategyCooldownAfterLossAfterConsecutive,
                                strategyId = riskOwnerStrategyId,
                            ),
                    )
                }
                perStrategyLossStreakHalt?.let {
                    perStrategyHaltRules.add(
                        com.qkt.risk.rules
                            .LossStreakHalt(
                                strategyId = riskOwnerStrategyId,
                                maxLosses = it,
                                ledger = pacerLedger,
                                scope = perStrategyLossStreakHaltScope,
                            ),
                    )
                }
            }
        }
    }
}
