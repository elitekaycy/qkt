package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class StructureCoordinatorTest {
    private val bus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())
    private val emitted = mutableListOf<Signal>()
    private val cancelled = mutableListOf<String>()
    private val shortPut =
        OrderRequest.Market(
            "s",
            "DERIBIT:BTC_USDC_9OCT26_81000_P",
            Side.SELL,
            BigDecimal("0.1"),
            TimeInForce.GTC,
            0L,
            "st",
        )
    private val longPut =
        OrderRequest.Market(
            "l",
            "DERIBIT:BTC_USDC_9OCT26_78000_P",
            Side.BUY,
            BigDecimal("0.1"),
            TimeInForce.GTC,
            0L,
            "st",
        )
    private val wing =
        OrderRequest.Market(
            "w",
            "DERIBIT:BTC_USDC_9OCT26_75000_P",
            Side.BUY,
            BigDecimal("0.1"),
            TimeInForce.GTC,
            0L,
            "st",
        )

    init {
        StructureCoordinator(bus) { cancelled += it }.bind("st") { emitted += it }
    }

    private fun open(vararg legs: OrderRequest) =
        bus.publish(SignalEvent(Signal.SubmitGroup("ps-1", legs.toList()), strategyId = "st"))

    private fun filled(leg: OrderRequest) =
        bus.publish(
            BrokerEvent.OrderFilled(
                leg.id,
                leg.id,
                leg.symbol,
                leg.side,
                BigDecimal.TEN,
                leg.quantity,
                strategyId = "st",
            ),
        )

    private fun cancelled(leg: OrderRequest) =
        bus.publish(BrokerEvent.OrderCancelled(leg.id, leg.id, "no bid", strategyId = "st"))

    @Test
    fun `a leg that fails after others filled closes the filled legs and cancels the working ones, shorts first`() {
        open(longPut, wing, shortPut)
        filled(longPut)
        filled(shortPut)

        cancelled(wing)

        assertThat(emitted).containsExactly(
            Signal.Buy("DERIBIT:BTC_USDC_9OCT26_81000_P", BigDecimal("0.1")),
            Signal.Sell("DERIBIT:BTC_USDC_9OCT26_78000_P", BigDecimal("0.1")),
        )
        assertThat(cancelled).isEmpty()
    }

    @Test
    fun `working legs are cancelled, and a leg filling after the failure is closed too`() {
        open(longPut, shortPut)
        cancelled(longPut)
        assertThat(cancelled).containsExactly("s")

        filled(shortPut)

        assertThat(emitted).containsExactly(Signal.Buy("DERIBIT:BTC_USDC_9OCT26_81000_P", BigDecimal("0.1")))
    }

    @Test
    fun `a fully filled structure and a group refused by risk need nothing`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        open(wing)
        bus.publish(RiskRejectedEvent(wing, "margin"))
        cancelled(wing)

        assertThat(emitted).isEmpty()
        assertThat(cancelled).isEmpty()
    }
}
