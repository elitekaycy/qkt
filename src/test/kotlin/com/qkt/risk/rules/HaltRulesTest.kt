package com.qkt.risk.rules

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.DrawdownBasis
import com.qkt.risk.HaltDecision
import com.qkt.risk.RiskState
import java.math.BigDecimal
import java.util.concurrent.atomic.AtomicLong
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class HaltRulesTest {
    private val seq = AtomicLong()

    private fun fill(
        strategyId: String,
        symbol: String,
        side: Side,
        qty: String,
        price: String,
    ) = BrokerEvent.OrderFilled(
        clientOrderId = "c-${seq.incrementAndGet()}",
        brokerOrderId = "b",
        symbol = symbol,
        side = side,
        price = Money.of(price),
        quantity = Money.of(qty),
        strategyId = strategyId,
        timestamp = 0L,
    )

    private class TestRig(
        val state: RiskState,
        val positions: PositionTracker,
        val strategyPositions: StrategyPositionTracker,
        val pnl: PnLCalculator,
        val strategyPnL: StrategyPnL,
    ) {
        fun applyAndRecord(event: BrokerEvent.OrderFilled) {
            val realized = positions.applyFill(event)
            pnl.recordRealized(realized)
            val stratRealized = strategyPositions.applyFill(event)
            strategyPnL.recordRealized(event.strategyId, stratRealized)
        }
    }

    private fun newRig(): TestRig {
        val clock = FixedClock(0L)
        val sequencer = MonotonicSequenceGenerator()
        val bus = EventBus(clock, sequencer)
        val prices = MarketPriceTracker()
        val positions = PositionTracker()
        val strategyPositions = StrategyPositionTracker()
        val pnl = PnLCalculator(positions, prices)
        val strategyPnL = StrategyPnL(strategyPositions, prices)
        val state = RiskState(pnl, strategyPnL, clock, bus)
        return TestRig(state, positions, strategyPositions, pnl, strategyPnL)
    }

    @Test
    fun `MaxDrawdown Continue when below threshold`() {
        val rig = newRig()
        rig.applyAndRecord(fill("A", "X", Side.BUY, "10", "100"))
        rig.state.equityTracker.update()
        assertThat(MaxDrawdown(BigDecimal("0.10"), DrawdownBasis.TRAILING).evaluate(rig.state))
            .isEqualTo(HaltDecision.Continue)
    }

    @Test
    fun `MaxDrawdown Halt when drawdown exceeds threshold`() {
        val rig = newRig()
        rig.applyAndRecord(fill("A", "X", Side.BUY, "10", "100"))
        rig.applyAndRecord(fill("A", "X", Side.SELL, "10", "120"))
        rig.state.equityTracker.update()
        // peak = 200 realized
        rig.applyAndRecord(fill("A", "X", Side.BUY, "10", "100"))
        rig.applyAndRecord(fill("A", "X", Side.SELL, "10", "80"))
        rig.state.equityTracker.update()
        // realized = 0; drawdown = 200/200 = 1.0
        assertThat(rig.state.drawdownTracker.globalDrawdown()).isEqualByComparingTo(BigDecimal("1.0"))
        val decision = MaxDrawdown(BigDecimal("0.5"), DrawdownBasis.TRAILING).evaluate(rig.state)
        assertThat(decision).isInstanceOf(HaltDecision.Halt::class.java)
    }

    @Test
    fun `MaxDailyLoss Continue when no loss`() {
        val rig = newRig()
        rig.state.dailyPnLTracker.recordRealized("A", BigDecimal("100"))
        assertThat(MaxDailyLoss(BigDecimal("50")).evaluate(rig.state))
            .isEqualTo(HaltDecision.Continue)
    }

    @Test
    fun `MaxDailyLoss Halt when loss exceeds threshold`() {
        val rig = newRig()
        rig.state.dailyPnLTracker.recordRealized("A", BigDecimal("-200"))
        val decision = MaxDailyLoss(BigDecimal("100")).evaluate(rig.state)
        assertThat(decision).isInstanceOf(HaltDecision.Halt::class.java)
        assertThat((decision as HaltDecision.Halt).strategyId).isNull()
    }

    @Test
    fun `MaxDailyLoss at exactly the threshold continues — strictly greater semantics`() {
        val rig = newRig()
        rig.state.dailyPnLTracker.recordRealized("A", BigDecimal("-100"))
        assertThat(MaxDailyLoss(BigDecimal("100")).evaluate(rig.state))
            .isEqualTo(HaltDecision.Continue)
        rig.state.dailyPnLTracker.recordRealized("A", BigDecimal("-0.01"))
        assertThat(MaxDailyLoss(BigDecimal("100")).evaluate(rig.state))
            .isInstanceOf(HaltDecision.Halt::class.java)
    }

    @Test
    fun `MaxStrategyDrawdown halts only that strategy`() {
        val rig = newRig()
        rig.applyAndRecord(fill("A", "X", Side.BUY, "10", "100"))
        rig.applyAndRecord(fill("A", "X", Side.SELL, "10", "120"))
        rig.state.equityTracker.updateStrategy("A")
        rig.applyAndRecord(fill("A", "X", Side.BUY, "10", "100"))
        rig.applyAndRecord(fill("A", "X", Side.SELL, "10", "80"))
        rig.state.equityTracker.updateStrategy("A")
        val decision = MaxStrategyDrawdown("A", BigDecimal("0.5"), DrawdownBasis.TRAILING).evaluate(rig.state)
        assertThat(decision).isInstanceOf(HaltDecision.Halt::class.java)
        assertThat((decision as HaltDecision.Halt).strategyId).isEqualTo("A")
    }

    @Test
    fun `MaxStrategyDailyLoss halts only that strategy`() {
        val rig = newRig()
        rig.state.dailyPnLTracker.recordRealized("A", BigDecimal("-300"))
        rig.state.dailyPnLTracker.recordRealized("B", BigDecimal("100"))
        val a = MaxStrategyDailyLoss("A", BigDecimal("100")).evaluate(rig.state)
        val b = MaxStrategyDailyLoss("B", BigDecimal("100")).evaluate(rig.state)
        assertThat(a).isInstanceOf(HaltDecision.Halt::class.java)
        assertThat((a as HaltDecision.Halt).strategyId).isEqualTo("A")
        assertThat(b).isEqualTo(HaltDecision.Continue)
    }
}
