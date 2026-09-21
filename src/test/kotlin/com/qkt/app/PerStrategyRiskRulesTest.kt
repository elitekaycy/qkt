package com.qkt.app

import com.qkt.common.FixedClock
import com.qkt.persistence.NoopStatePersistor
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltScope
import com.qkt.risk.PacerLedger
import com.qkt.risk.rules.MaxStrategyDailyLoss
import com.qkt.risk.rules.MaxTradesPerDay
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class PerStrategyRiskRulesTest {
    private fun limits(
        maxDailyLoss: BigDecimal? = null,
        maxTradesPerDay: Int? = null,
    ) = PerStrategyRiskLimits(
        perStrategyMaxDailyLoss = maxDailyLoss,
        perStrategyMaxPositionSize = null,
        perStrategyMaxOpenPositions = null,
        perStrategyMaxDrawdownPct = null,
        perStrategyMaxDailyDrawdownPct = null,
        perStrategyMaxTradesPerDay = maxTradesPerDay,
        perStrategyCooldownAfterLossMs = null,
        perStrategyCooldownAfterLossAfterConsecutive = 1,
        perStrategyLossStreakHalt = null,
        perStrategyLossStreakHaltScope = HaltScope.PERSISTENT,
    )

    private fun rules(
        limits: PerStrategyRiskLimits,
        owner: String?,
    ) = PerStrategyRiskRules(
        limits,
        owner,
        StrategyPositionTracker(NoopStatePersistor()),
        emptyMap(),
        BigDecimal.ZERO,
        DrawdownBasis.STATIC,
        PacerLedger(),
        FixedClock(),
    )

    @Test
    fun `a loss limit halts and a trade cap gates entries`() {
        val built = rules(limits(maxDailyLoss = BigDecimal("500"), maxTradesPerDay = 3), "gold")

        assertThat(built.haltRules).hasSize(1).first().isInstanceOf(MaxStrategyDailyLoss::class.java)
        assertThat(built.riskRules).hasSize(1).first().isInstanceOf(MaxTradesPerDay::class.java)
    }

    @Test
    fun `a session with no owning strategy builds no rules even with limits set`() {
        val built = rules(limits(maxDailyLoss = BigDecimal("500"), maxTradesPerDay = 3), null)

        assertThat(built.haltRules).isEmpty()
        assertThat(built.riskRules).isEmpty()
    }
}
