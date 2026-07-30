package com.qkt.risk

import com.qkt.common.Clock
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.rules.CooldownAfterLoss
import com.qkt.risk.rules.LossStreakHalt
import com.qkt.risk.rules.MaxStrategyDailyDrawdown
import com.qkt.risk.rules.MaxStrategyDailyLoss
import com.qkt.risk.rules.MaxStrategyDrawdown
import com.qkt.risk.rules.MaxStrategyOpenPositions
import com.qkt.risk.rules.MaxStrategyPositionSize
import com.qkt.risk.rules.MaxTradesPerDay
import java.math.BigDecimal

internal data class StrategyRiskRuleSet(
    val riskRules: List<RiskRule>,
    val haltRules: List<HaltRule>,
)

internal object StrategyRiskRuleFactory {
    fun build(
        strategyIds: Collection<String>,
        limitsByStrategy: Map<String, StrategyRiskLimits>,
        strategyPositions: StrategyPositionTracker,
        pacerLedger: PacerLedger,
        clock: Clock,
        totalDdBasis: DrawdownBasis,
        startingBalance: BigDecimal,
        startingBalances: Map<String, BigDecimal>,
    ): StrategyRiskRuleSet {
        val riskRules = mutableListOf<RiskRule>()
        val haltRules = mutableListOf<HaltRule>()
        for (strategyId in strategyIds) {
            val limits = limitsByStrategy[strategyId] ?: continue
            limits.maxDailyLoss?.let {
                haltRules.add(MaxStrategyDailyLoss(strategyId, it))
            }
            limits.maxPositionSize?.let {
                riskRules.add(MaxStrategyPositionSize(strategyId, it, strategyPositions))
            }
            limits.maxOpenPositions?.let {
                riskRules.add(MaxStrategyOpenPositions(strategyId, it, strategyPositions))
            }
            val strategyStartingBalance = startingBalances[strategyId] ?: startingBalance
            limits.maxDrawdownPct?.let {
                haltRules.add(MaxStrategyDrawdown(strategyId, it, totalDdBasis, strategyStartingBalance))
            }
            limits.maxDailyDrawdownPct?.let {
                haltRules.add(MaxStrategyDailyDrawdown(strategyId, it))
            }
            limits.maxTradesPerDay?.let {
                riskRules.add(MaxTradesPerDay(it, pacerLedger, clock, strategyId))
            }
            limits.cooldownAfterLossMs?.let {
                riskRules.add(
                    CooldownAfterLoss(
                        durationMs = it,
                        ledger = pacerLedger,
                        clock = clock,
                        afterConsecutive = limits.cooldownAfterLossAfterConsecutive,
                        strategyId = strategyId,
                    ),
                )
            }
            limits.lossStreakHalt?.let {
                haltRules.add(
                    LossStreakHalt(
                        strategyId = strategyId,
                        maxLosses = it,
                        ledger = pacerLedger,
                        scope = limits.lossStreakHaltScope,
                    ),
                )
            }
        }
        return StrategyRiskRuleSet(riskRules, haltRules)
    }
}
