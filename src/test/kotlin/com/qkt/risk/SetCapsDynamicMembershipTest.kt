package com.qkt.risk

import com.qkt.cli.PerStrategyRisk
import com.qkt.cli.liveRiskLimits
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.dsl.sets.DynamicSetMembership
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.EventContracts
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * Set caps over a set whose membership moves (#1714 step 9): a cap bound through [liveRiskLimits] to a
 * [DynamicSetMembership]'s active members guards whoever is active at each order, sticky-held members included, so a
 * member is capped from the step it joins until the step it is released, and a cap is never escaped by a member that
 * left the ranking while it still holds.
 */
class SetCapsDynamicMembershipTest {
    private val hour = 3_600_000L
    private val h0 = 1_794_002_400_000L

    private fun contract(
        code: String,
        open: Int,
        expiry: Int,
    ) = EventContracts.meta(
        "KALSHI:$code",
        EventContracts.terms().copy(
            openAtMs = h0 + open * hour,
            closeAtMs = h0 + expiry * hour,
            expiryMs = h0 + expiry * hour,
        ),
    )

    private val membership =
        DynamicSetMembership(
            SymbolSetSpec.parse("KALSHI:* WHERE kind = event TOP 2 BY symbol ASC DYNAMIC") as SymbolSetSpec.Filtered,
            listOf(contract("A", 0, 3), contract("B", 1, 5), contract("C", 2, 6)),
            hour,
        )

    private val risk = PerStrategyRisk(sets = mapOf("m" to SetCaps(maxPositionSize = BigDecimal("3"))))

    private val cap =
        StrategyRiskRuleFactory
            .build(
                strategyIds = listOf("events"),
                limitsByStrategy =
                    mapOf(
                        "events" to
                            liveRiskLimits("events", risk, emptyList(), mapOf("m" to { membership.activeSymbols }))!!,
                    ),
                strategyPositions = StrategyPositionTracker(),
                pacerLedger = PacerLedger(),
                clock = FixedClock(0L),
                totalDdBasis = DrawdownBasis.TRAILING,
                startingBalance = BigDecimal("10000"),
                startingBalances = emptyMap(),
            ).riskRules
            .single()

    private val global = StrategyPositionTracker().account

    private fun capped(code: String): Boolean {
        val order =
            OrderRequest.Market(
                id = "ORD-0",
                symbol = "KALSHI:$code",
                side = Side.BUY,
                quantity = Money.of("5"),
                timeInForce = TimeInForce.GTC,
                timestamp = 1000L,
                strategyId = "events",
            )
        return cap.evaluate(order, global) is Decision.Reject
    }

    @Test
    fun `a cap follows the set's active members from join to release, sticky-held ones included`() {
        // before the first step no contract is a member: nothing is capped, and the cap is valid with no member stream
        assertThat(listOf("A", "B", "C").map(::capped)).containsOnly(false)

        membership.at(h0) { emptySet() }
        assertThat(capped("A")).isTrue()
        assertThat(capped("B")).isFalse()

        membership.at(h0 + hour) { emptySet() }
        assertThat(listOf(capped("A"), capped("B"), capped("C"))).containsExactly(true, true, false)

        // A leaves the top 2 but still holds: it stays active, sticky, and stays capped beside the new member C
        membership.at(h0 + 3 * hour) { setOf("A") }
        assertThat(membership.activeSymbols).containsExactlyInAnyOrder("KALSHI:A", "KALSHI:B", "KALSHI:C")
        assertThat(listOf(capped("A"), capped("B"), capped("C"))).containsExactly(true, true, true)

        // A is flat now: released, so no longer capped
        membership.at(h0 + 4 * hour) { emptySet() }
        assertThat(listOf(capped("A"), capped("B"), capped("C"))).containsExactly(false, true, true)
    }
}
