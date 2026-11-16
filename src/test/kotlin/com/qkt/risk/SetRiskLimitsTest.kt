package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class SetRiskLimitsTest {
    private val members = setOf("ALPACA:AAPL", "ALPACA:MSFT")

    @Test
    fun `set limits and caps validate their members and bounds`() {
        assertThat(SetRiskLimits(members, 2, BigDecimal("5")).members).isEqualTo(members)
        assertThat(SetRiskLimits(members).maxOpenPositions).isNull()
        assertThatThrownBy { SetRiskLimits(emptySet(), 1) }.hasMessage("SetRiskLimits needs at least one member symbol")
        assertThatThrownBy { SetRiskLimits(members, 0) }.hasMessage("SetRiskLimits.maxOpenPositions must be > 0: 0")
        assertThatThrownBy {
            SetRiskLimits(members, 1, BigDecimal.ZERO)
        }.hasMessage("SetRiskLimits.maxPositionSize must be > 0: 0")
        assertThat(SetCaps().maxPositionSize).isNull()
        assertThatThrownBy { SetCaps(maxOpenPositions = -1) }.hasMessage("SetCaps.maxOpenPositions must be > 0: -1")
        assertThatThrownBy {
            SetCaps(
                maxPositionSize = BigDecimal("-2"),
            )
        }.hasMessage("SetCaps.maxPositionSize must be > 0: -2")
    }

    @Test
    fun `the factory builds one rule per set cap and none for an empty cap`() {
        val rules =
            StrategyRiskRuleFactory.build(
                strategyIds = listOf("rotation"),
                limitsByStrategy =
                    mapOf(
                        "rotation" to
                            StrategyRiskLimits(
                                sets =
                                    mapOf(
                                        "stocks" to
                                            SetRiskLimits(
                                                members,
                                                maxOpenPositions = 2,
                                                maxPositionSize = BigDecimal("5"),
                                            ),
                                        "fx" to SetRiskLimits(setOf("EXNESS:EURUSD"), maxOpenPositions = 1),
                                        "idle" to SetRiskLimits(setOf("EXNESS:XAUUSD")),
                                    ),
                            ),
                    ),
                strategyPositions = StrategyPositionTracker(),
                pacerLedger = PacerLedger(),
                clock = FixedClock(0L),
                totalDdBasis = DrawdownBasis.TRAILING,
                startingBalance = BigDecimal("10000"),
                startingBalances = emptyMap(),
            )
        assertThat(rules.riskRules.map { it::class.simpleName })
            .containsExactly("MaxSetPositionSize", "MaxSetOpenPositions", "MaxSetOpenPositions")
        assertThat(rules.haltRules).isEmpty()
    }
}
