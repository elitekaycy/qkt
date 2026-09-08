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

    /** A position view reporting [n] live, not-yet-filled entry orders on the buy side. */
    private fun withInFlightBuys(n: Int) =
        object : com.qkt.positions.PositionProvider by StrategyPositionTracker().account {
            override fun pendingEntryOrderCount(
                side: Side,
                strategyId: String?,
            ): Int = if (side == Side.BUY) n else 0
        }

    @Test
    fun `a burst cannot outrun the daily cap while its entries are still in flight`() {
        // The live failure this guards. Counting only FILLED entries let a whole burst pass: every
        // order is risk-checked before any of it fills, so each read the same pre-burst total. A
        // strategy capped at sixty executed a hundred in one burst live, while the backtest --
        // where fills land between submissions -- stopped it at sixty.
        val clock = FixedClock(1_705_276_800_000L + 10_000L)
        val rule = MaxTradesPerDay(maxTrades = 60, ledger = PacerLedger(), clock = clock)

        assertThat(rule.evaluate(order(), withInFlightBuys(59))).isEqualTo(Decision.Approve)
        assertThat(rule.evaluate(order(), withInFlightBuys(60)))
            .isInstanceOf(Decision.Reject::class.java)
    }

    @Test
    fun `an open position's protective legs are not counted as pending entries`() {
        // The side filter is load-bearing. A filled long rests its stop and target on the SELL
        // side; counting both sides reported one phantom pending entry per open position and made
        // the cap bind at half its setting in replay, where positions fill as the burst is placed.
        val clock = FixedClock(1_705_276_800_000L + 10_000L)
        val ledger = PacerLedger()
        repeat(30) { ledger.recordEntryFill("s", clock.now() - 1_000L) }
        val rule = MaxTradesPerDay(maxTrades = 60, ledger = ledger, clock = clock)

        // 30 filled longs, each with protective legs resting on the sell side, and no live entries
        assertThat(rule.evaluate(order(), withInFlightBuys(0))).isEqualTo(Decision.Approve)
    }

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
