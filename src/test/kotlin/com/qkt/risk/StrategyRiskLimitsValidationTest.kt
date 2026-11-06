package com.qkt.risk

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StrategyRiskLimitsValidationTest {
    @Test
    fun `drawdown limits must be fractions in zero to one`() {
        assertThatThrownBy { StrategyRiskLimits(maxDrawdownPct = BigDecimal("1.5")) }
            .hasMessage("StrategyRiskLimits.maxDrawdownPct must be in (0, 1]: 1.5")
        assertThatThrownBy { StrategyRiskLimits(maxDailyDrawdownPct = BigDecimal.ZERO) }
            .hasMessage("StrategyRiskLimits.maxDailyDrawdownPct must be in (0, 1]: 0")
        assertThat(StrategyRiskLimits(maxDrawdownPct = BigDecimal.ONE).maxDrawdownPct).isEqualTo(BigDecimal.ONE)
    }

    @Test
    fun `a cooldown must follow at least one consecutive loss`() {
        assertThatThrownBy { StrategyRiskLimits(cooldownAfterLossAfterConsecutive = 0) }
            .hasMessage("StrategyRiskLimits.cooldownAfterLossAfterConsecutive must be > 0: 0")
    }

    @Test
    fun `counts sizes and durations must be positive`() {
        assertThatThrownBy { StrategyRiskLimits(maxPositionSize = BigDecimal("-1")) }
            .hasMessage("StrategyRiskLimits.maxPositionSize must be > 0: -1")
        assertThatThrownBy { StrategyRiskLimits(maxOpenPositions = 0) }
            .hasMessage("StrategyRiskLimits.maxOpenPositions must be > 0: 0")
        assertThatThrownBy { StrategyRiskLimits(cooldownAfterLossMs = 0L) }
            .hasMessage("StrategyRiskLimits.cooldownAfterLossMs must be > 0: 0")
    }

    @Test
    fun `a pacer view without a cooldown never reports one and the inert view reports nothing`() {
        val ledger = PacerLedger()
        val view = PacerViewImpl(ledger, "s", cooldownDurationMs = null)

        assertThat(view.cooldownRemainingSeconds(1_000L)).isZero()
        assertThat(view.tradesToday(1_000L)).isZero()
        assertThat(NoOpPacerView().tradesToday(1_000L)).isZero()
        assertThat(NoOpPacerView().cooldownRemainingSeconds(1_000L)).isZero()
    }

    @Test
    fun `a cooldown that waits for no losses is refused`() {
        assertThatThrownBy { PacerLedger().cooldownRemainingMs("s", 0L, 1_000L, afterConsecutive = 0) }
            .hasMessage("afterConsecutive must be > 0: 0")
    }
}
