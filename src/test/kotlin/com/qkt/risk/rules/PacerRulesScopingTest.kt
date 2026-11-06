package com.qkt.risk.rules

import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.positions.IntentBook
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.HaltDecision
import com.qkt.risk.HaltScope
import com.qkt.risk.PacerLedger
import com.qkt.risk.RiskState
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PacerRulesScopingTest {
    private val clock = FixedClock(2_000L)
    private val ledger = PacerLedger().apply { recordOutcome("s", 1_000L, BigDecimal("-10")) }
    private val flat = StrategyPositionTracker().account

    private fun order(
        strategyId: String,
        side: Side = Side.BUY,
    ) = OrderRequest.Market("o", "XAUUSD", side, BigDecimal.ONE, TimeInForce.GTC, 0L, strategyId)

    @Test
    fun `pacer limits refuse non-positive settings and a blank strategy`() {
        assertThatThrownBy { MaxTradesPerDay(0, ledger, clock) }.hasMessage("maxTrades must be > 0: 0")
        assertThatThrownBy { CooldownAfterLoss(0L, ledger, clock) }.hasMessage("durationMs must be > 0: 0")
        assertThatThrownBy { CooldownAfterLoss(1L, ledger, clock, afterConsecutive = 0) }
            .hasMessage("afterConsecutive must be > 0: 0")
        assertThatThrownBy { LossStreakHalt(" ", 1, ledger, clock) }.hasMessage("strategyId must be non-blank")
        assertThatThrownBy { LossStreakHalt("s", 0, ledger, clock) }.hasMessage("maxLosses must be > 0: 0")
    }

    @Test
    fun `a cooldown scoped to one strategy leaves the others alone`() {
        val rule = CooldownAfterLoss(5_000L, ledger, clock, strategyId = "s")

        assertThat(rule.evaluate(order("s"), flat)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("t"), flat)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a daily cap scoped to one strategy leaves the others alone`() {
        ledger.recordEntryFill("s", 1_500L)
        val rule = MaxTradesPerDay(1, ledger, clock, strategyId = "s")

        assertThat(rule.evaluate(order("s"), flat)).isInstanceOf(Decision.Reject::class.java)
        assertThat(rule.evaluate(order("t"), flat)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a cooldown lets a risk-reducing exit out`() {
        val ledgerBook = StrategyPositionTracker()
        val fill = BrokerEvent.OrderFilled("e", "e", "XAUUSD", Side.BUY, BigDecimal("100"), BigDecimal.ONE, "s", 1L)
        IntentBook().apply(ledgerBook, fill)
        val rule = CooldownAfterLoss(5_000L, ledger, clock)

        assertThat(rule.evaluate(order("s", Side.SELL), ledgerBook.account)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `a daily loss streak halt with no losses recorded continues`() {
        val rule = LossStreakHalt("fresh", 1, PacerLedger(), clock, scope = HaltScope.DAILY)

        assertThat(rule.evaluate(RiskState.noOp(clock))).isEqualTo(HaltDecision.Continue)
    }
}
