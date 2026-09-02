package com.qkt.risk.rules

import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.Side
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
import org.junit.jupiter.api.Test

class PacerRulesTest {
    private fun order(
        side: Side = Side.BUY,
        qty: BigDecimal = Money.of("1"),
        strategyId: String = "s",
    ) = OrderRequest.Market(
        id = "o",
        symbol = "XAUUSD",
        side = side,
        quantity = qty,
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        strategyId = strategyId,
    )

    @Test
    fun `max trades per day rejects new risk at the cap`() {
        val clock = FixedClock(1_705_276_800_000L + 10_000L)
        val ledger = PacerLedger()
        ledger.recordEntryFill("s", clock.now() - 1_000L)
        val rule = MaxTradesPerDay(maxTrades = 1, ledger = ledger, clock = clock)

        assertThat(rule.evaluate(order(), StrategyPositionTracker().account))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `max trades per day allows risk reducing exits`() {
        val clock = FixedClock(10_000L)
        val ledger = PacerLedger()
        ledger.recordEntryFill("s", 1_000L)
        val strategyPositions = StrategyPositionTracker()
        val positions = strategyPositions.account
        IntentBook().apply(strategyPositions, fill(Side.BUY, Money.of("1")))
        val rule = MaxTradesPerDay(maxTrades = 1, ledger = ledger, clock = clock)

        assertThat(rule.evaluate(order(side = Side.SELL), positions)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `cooldown after loss rejects until duration elapses`() {
        val clock = FixedClock(2_000L)
        val ledger = PacerLedger()
        ledger.recordOutcome("s", 1_000L, BigDecimal("-10"))
        val rule = CooldownAfterLoss(durationMs = 5_000L, ledger = ledger, clock = clock)

        assertThat(rule.evaluate(order(), StrategyPositionTracker().account))
            .isInstanceOf(Decision.Reject::class.java)

        clock.time = 6_001L
        assertThat(rule.evaluate(order(), StrategyPositionTracker().account)).isEqualTo(Decision.Approve)
    }

    @Test
    fun `loss streak halt halts only the configured strategy`() {
        val ledger = PacerLedger()
        ledger.recordOutcome("s", 1_000L, BigDecimal("-10"))
        ledger.recordOutcome("s", 2_000L, BigDecimal("-5"))
        val rule = LossStreakHalt("s", maxLosses = 2, ledger = ledger, scope = HaltScope.DAILY)

        val decision = rule.evaluate(RiskState.noOp())

        assertThat(decision).isEqualTo(
            HaltDecision.Halt(
                reason = "LossStreakHalt[s]: 2 consecutive losses, max 2",
                strategyId = "s",
                scope = HaltScope.DAILY,
            ),
        )
    }

    private fun fill(
        side: Side,
        qty: BigDecimal,
    ) = com.qkt.events.BrokerEvent.OrderFilled(
        clientOrderId = "fill",
        brokerOrderId = "b",
        symbol = "XAUUSD",
        side = side,
        price = Money.of("100"),
        quantity = qty,
        strategyId = "s",
        timestamp = 0L,
    )
}
