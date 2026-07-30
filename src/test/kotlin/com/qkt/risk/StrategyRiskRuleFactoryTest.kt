package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StrategyRiskRuleFactoryTest {
    @Test
    fun `build creates every configured per-strategy rule`() {
        val rules =
            StrategyRiskRuleFactory.build(
                strategyIds = listOf("alpha"),
                limitsByStrategy =
                    mapOf(
                        "alpha" to
                            StrategyRiskLimits(
                                maxDailyLoss = BigDecimal("100"),
                                maxPositionSize = BigDecimal("2"),
                                maxOpenPositions = 3,
                                maxDrawdownPct = BigDecimal("0.10"),
                                maxDailyDrawdownPct = BigDecimal("0.05"),
                                maxTradesPerDay = 4,
                                cooldownAfterLossMs = 60_000L,
                                lossStreakHalt = 2,
                            ),
                    ),
                strategyPositions = StrategyPositionTracker(),
                pacerLedger = PacerLedger(),
                clock = FixedClock(0L),
                totalDdBasis = DrawdownBasis.TRAILING,
                startingBalance = BigDecimal("10000"),
                startingBalances = emptyMap(),
            )

        assertThat(rules.riskRules.mapNotNull { it::class.simpleName })
            .containsExactlyInAnyOrder(
                "MaxStrategyPositionSize",
                "MaxStrategyOpenPositions",
                "MaxTradesPerDay",
                "CooldownAfterLoss",
            )
        assertThat(rules.haltRules.mapNotNull { it::class.simpleName })
            .containsExactlyInAnyOrder(
                "MaxStrategyDailyLoss",
                "MaxStrategyDrawdown",
                "MaxStrategyDailyDrawdown",
                "LossStreakHalt",
            )
    }
}
