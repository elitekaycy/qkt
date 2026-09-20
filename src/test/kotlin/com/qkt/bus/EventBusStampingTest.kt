package com.qkt.bus

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.CandleEvent
import com.qkt.events.Event
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.strategy.Signal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EventBusStampingTest : EventBusFixture() {
    @Test
    fun `bus stamps timestamp from clock on publish`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 12345L
        bus.publish(TickEvent(tick()))

        assertThat(received[0].timestamp).isEqualTo(12345L)
    }

    @Test
    fun `bus stamps CandleEvent on publish`() {
        val bus = newBus()
        val received = mutableListOf<CandleEvent>()
        bus.subscribe<CandleEvent> { received.add(it) }

        clock.time = 7777L
        bus.publish(
            CandleEvent(
                Candle(
                    "XAUUSD",
                    open = Money.of("100.0"),
                    high = Money.of("101.0"),
                    low = Money.of("99.0"),
                    close = Money.of("100.5"),
                    volume = Money.of("12.0"),
                    startTime = 0L,
                    endTime = 60_000L,
                ),
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received[0].timestamp).isEqualTo(7777L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
        assertThat(received[0].candle.symbol).isEqualTo("XAUUSD")
    }

    @Test
    fun `bus stamps RiskRejectedEvent on publish`() {
        val bus = newBus()
        val received = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { received.add(it) }

        clock.time = 8888L
        bus.publish(
            RiskRejectedEvent(
                request =
                    OrderRequest.Market(
                        id = "ORD-9",
                        symbol = "XAUUSD",
                        side = Side.BUY,
                        quantity = Money.of("1"),
                        timeInForce = TimeInForce.GTC,
                        timestamp = 1000L,
                    ),
                reason = "test rejection",
            ),
        )

        assertThat(received).hasSize(1)
        assertThat(received[0].timestamp).isEqualTo(8888L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
        assertThat(received[0].request.id).isEqualTo("ORD-9")
        assertThat(received[0].reason).isEqualTo("test rejection")
    }

    @Test
    fun `bus stamps monotonic sequenceId on publish`() {
        val bus = newBus()
        val received = mutableListOf<Event>()
        bus.subscribe<TickEvent> { received.add(it) }
        bus.subscribe<SignalEvent> { received.add(it) }

        bus.publish(TickEvent(tick()))
        bus.publish(SignalEvent(Signal.Buy("XAUUSD", Money.of("1"))))
        bus.publish(TickEvent(tick()))

        assertThat(received.map { it.sequenceId }).containsExactly(0L, 1L, 2L)
    }

    @Test
    fun `published event default timestamp and sequenceId are overwritten`() {
        val bus = newBus()
        val received = mutableListOf<TickEvent>()
        bus.subscribe<TickEvent> { received.add(it) }

        clock.time = 5555L
        bus.publish(TickEvent(tick(), timestamp = 999L, sequenceId = 999L))

        assertThat(received[0].timestamp).isEqualTo(5555L)
        assertThat(received[0].sequenceId).isEqualTo(0L)
    }
}
