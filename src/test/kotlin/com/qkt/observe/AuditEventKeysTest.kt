package com.qkt.observe

import com.qkt.app.LatchManagerFixture
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.ContractSettled
import com.qkt.events.CostIncurred
import com.qkt.events.DecisionOrderLinkedEvent
import com.qkt.events.FundingCharged
import com.qkt.events.OrderEvent
import com.qkt.events.RiskEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.SignalSuppressedEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.Trade
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AuditEventKeysTest {
    private val one = BigDecimal.ONE

    private fun order(strategy: String) =
        OrderRequest.Market("o-1", "XAUUSD", Side.BUY, one, TimeInForce.GTC, 1L, strategy)

    @Test
    fun `a signal is indexed by the symbol it targets, or none for a latch or structure`() {
        fun symbolOf(signal: Signal) = auditSymbol(SignalEvent(signal, strategyId = "s"))

        assertThat(symbolOf(Signal.Sell("EURUSD", one))).isEqualTo("EURUSD")
        assertThat(symbolOf(Signal.Submit(order("s")))).isEqualTo("XAUUSD")
        assertThat(symbolOf(Signal.CancelPendingForSymbol("GBPUSD"))).isEqualTo("GBPUSD")
        assertThat(symbolOf(Signal.Suppressed("USDJPY", "cooldown"))).isEqualTo("USDJPY")
        assertThat(symbolOf(Signal.SubmitGroup("st-1", "spread", listOf(order("s"))))).isNull()
        val latch =
            Signal.ArmLatch(
                LatchManagerFixture.compiledLatch("2000", "5", 1_000L),
                LatchManagerFixture.ec("XAUUSD"),
            )
        assertThat(symbolOf(latch)).isNull()
    }

    @Test
    fun `a blank owning strategy is indexed as engine wide`() {
        val events =
            listOf(
                SignalEvent(Signal.Buy("X", one), strategyId = ""),
                OrderEvent(order("")),
                BrokerEvent.OrderCancelled("o-1", null, "user", strategyId = ""),
                RiskRejectedEvent(order(""), "limit"),
                RiskEvent.Halted("loss", strategyId = " "),
                RiskEvent.Resumed(strategyId = null),
                DecisionOrderLinkedEvent("", "d", "r", 0, "o-1"),
                TradeEvent(Trade("o-1", "X", one, one, Side.BUY, 1L)),
                SignalSuppressedEvent(Signal.Buy("X", one), "", "paused"),
                CostIncurred("", "X", one, "fee", null),
            )
        events.forEach { assertThat(auditStrategyId(it)).describedAs(it::class.simpleName).isNull() }
    }

    @Test
    fun `an owned event is indexed by its strategy`() {
        val events =
            listOf(
                RiskRejectedEvent(order("s"), "limit"),
                RiskEvent.Halted("loss", strategyId = "s"),
                RiskEvent.Resumed(strategyId = "s"),
                DecisionOrderLinkedEvent("s", "d", "r", 0, "o-1"),
                TradeEvent(Trade("o-1", "X", one, one, Side.BUY, 1L), strategyId = "s"),
                SignalSuppressedEvent(Signal.Buy("X", one), "s", "paused"),
            )
        events.forEach { assertThat(auditStrategyId(it)).describedAs(it::class.simpleName).isEqualTo("s") }
    }

    @Test
    fun `order ids and symbols come from the event that carries them`() {
        val trade = TradeEvent(Trade("t-1", "XAGUSD", one, one, Side.SELL, 1L))
        assertThat(auditOrderId(trade)).isEqualTo("t-1")
        assertThat(auditSymbol(trade)).isEqualTo("XAGUSD")
        assertThat(auditOrderId(RiskRejectedEvent(order("s"), "limit"))).isEqualTo("o-1")
        assertThat(auditOrderId(DecisionOrderLinkedEvent("s", "d", "r", 0, "o-7"))).isEqualTo("o-7")
        assertThat(auditOrderId(RiskEvent.Resumed(strategyId = "s"))).isNull()
        assertThat(auditSymbol(ContractSettled("BTC-27DEC", one))).isEqualTo("BTC-27DEC")
        assertThat(auditSymbol(FundingCharged("f", "BTC-PERP", one, "USD", one, 1L))).isEqualTo("BTC-PERP")
        assertThat(
            auditSymbol(BrokerEvent.PositionReconciled("ETH", null, one, null, one, "poll", "drift")),
        ).isEqualTo("ETH")
        assertThat(
            auditSymbol(BrokerEvent.OrderPartiallyFilled("o", null, "SOL", Side.BUY, one, one, one)),
        ).isEqualTo("SOL")
        assertThat(auditSymbol(RiskRejectedEvent(order("s"), "limit"))).isEqualTo("XAUUSD")
        assertThat(auditSymbol(RiskEvent.Resumed(strategyId = null))).isNull()
    }
}
