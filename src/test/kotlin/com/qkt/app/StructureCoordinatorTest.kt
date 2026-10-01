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
    private val clock = FixedClock(5L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
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
        StructureCoordinator(bus, clock) { cancelled += it }.bind("st") { signal ->
            emitted += signal
            bus.publish(SignalEvent(signal, strategyId = "st"))
        }
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

    private fun cancelled(id: String) = bus.publish(BrokerEvent.OrderCancelled(id, id, "no bid", strategyId = "st"))

    private fun unwinds() = emitted.filterIsInstance<Signal.SubmitGroup>()

    @Test
    fun `a failed leg unwinds the filled legs as one forced group, shorts bought back first`() {
        open(longPut, wing, shortPut)
        filled(longPut)
        filled(shortPut)

        cancelled("w")

        val unwind = unwinds().single()
        assertThat(unwind.force).isTrue()
        assertThat(unwind.requests.map { Triple(it.symbol, it.side, it.quantity) }).containsExactly(
            Triple("DERIBIT:BTC_USDC_9OCT26_81000_P", Side.BUY, BigDecimal("0.1")),
            Triple("DERIBIT:BTC_USDC_9OCT26_78000_P", Side.SELL, BigDecimal("0.1")),
        )
        assertThat(unwind.requests).allMatch { it.strategyId == "st" && it.timestamp == 5L }
        assertThat(cancelled).isEmpty()
    }

    @Test
    fun `working legs are cancelled, a late fill is unwound, and a cancelled close is sent again`() {
        open(longPut, shortPut)
        cancelled("l")
        assertThat(cancelled).containsExactly("s")

        filled(shortPut)
        val close = unwinds().single().requests.single()
        assertThat(close.side).isEqualTo(Side.BUY)

        cancelled(close.id)

        val retry = unwinds().last().requests.single()
        assertThat(retry.id).isNotEqualTo(close.id)
        assertThat(
            Triple(retry.symbol, retry.side, retry.quantity),
        ).isEqualTo(Triple(close.symbol, close.side, close.quantity))
    }

    @Test
    fun `a fully filled structure and a group refused by risk need nothing`() {
        open(longPut, shortPut)
        filled(longPut)
        filled(shortPut)
        open(wing)
        bus.publish(RiskRejectedEvent(wing, "margin"))
        cancelled("w")

        assertThat(emitted).isEmpty()
        assertThat(cancelled).isEmpty()
    }
}
