package com.qkt.risk

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.StrategyPositionTracker
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HaltedGroupAndCooldownTest {
    @Test
    fun `a halted strategy's group that adds exposure is refused with the halt reason`() {
        val state = RiskState.noOp(FixedClock(0L))
        state.haltStrategy("s", "daily loss")
        val engine = RiskEngine(emptyList(), emptyList(), StrategyPositionTracker().account, state)
        val buy = OrderRequest.Market("o", "XAUUSD", Side.BUY, BigDecimal.ONE, TimeInForce.GTC, 0L, "s")

        assertThat(engine.approveGroup(listOf(buy))).isEqualTo(Decision.Reject("halted: daily loss"))
        assertThat(engine.approveGroup(listOf(buy.copy(strategyId = "other")))).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a loss starts a cooldown counted down in the pacer view`() {
        val ledger = PacerLedger()
        ledger.recordOutcome("s", 1_000L, BigDecimal("-5"))

        assertThat(ledger.cooldownRemainingMs("s", nowMs = 31_000L, durationMs = 60_000L)).isEqualTo(30_000L)
        assertThat(PacerViewImpl(ledger, "s", cooldownDurationMs = 60_000L).cooldownRemainingSeconds(31_000L))
            .isEqualTo(30L)
        assertThat(PacerViewImpl(ledger, "s", cooldownDurationMs = null).cooldownRemainingSeconds(31_000L)).isZero()
    }
}
