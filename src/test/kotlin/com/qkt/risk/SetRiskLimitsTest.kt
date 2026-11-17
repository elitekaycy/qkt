package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
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
    fun `a set whose membership moves reads its members from the supplier, and may start with none`() {
        var now = setOf("ALPACA:AAPL")
        val moving = SetRiskLimits(emptySet(), 1, active = { now })

        assertThat(moving.current()).containsExactly("ALPACA:AAPL")
        now = setOf("ALPACA:MSFT", "ALPACA:NVDA")
        assertThat(moving.current()).containsExactlyInAnyOrder("ALPACA:MSFT", "ALPACA:NVDA")
        assertThat(SetRiskLimits(members, 1, active = { now }).members).isEqualTo(members)
        assertThat(SetRiskLimits(members, 1).current()).isEqualTo(members)
    }

    @Test
    fun `a cap built from a moving set follows its members at each order, sticky-held ones included`() {
        var now = setOf("ALPACA:AAPL")
        val rules =
            StrategyRiskRuleFactory.build(
                strategyIds = listOf("rotation"),
                limitsByStrategy =
                    mapOf(
                        "rotation" to
                            StrategyRiskLimits(
                                sets =
                                    mapOf(
                                        "moving" to
                                            SetRiskLimits(now, maxPositionSize = BigDecimal("3"), active = { now }),
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
        val global = StrategyPositionTracker().account
        val big = order("ALPACA:NVDA", Money.of("5"))
        val rule = rules.riskRules.single()

        // NVDA is not in the set yet: untouched
        assertThat(rule.evaluate(big, global)).isEqualTo(Decision.Approve)
        // it joins, so its size is capped from the next order
        now = now + "ALPACA:NVDA"
        assertThat(rule.evaluate(big, global)).isInstanceOf(Decision.Reject::class.java)
        // it drops out of the active set (flat, released): no longer capped
        now = setOf("ALPACA:AAPL")
        assertThat(rule.evaluate(big, global)).isEqualTo(Decision.Approve)
    }

    private fun order(
        symbol: String,
        qty: BigDecimal,
    ) = OrderRequest.Market(
        id = "ORD-0",
        symbol = symbol,
        side = Side.BUY,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 1000L,
        strategyId = "rotation",
    )

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
