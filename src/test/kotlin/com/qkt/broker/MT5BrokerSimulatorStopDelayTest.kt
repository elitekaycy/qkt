package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.LegIntent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TriggerType
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MT5BrokerSimulatorStopDelayTest {
    private val symbol = "EXNESS:XAUUSD"
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val events = mutableListOf<BrokerEvent>()
    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal("100"),
                    volumeStep = BigDecimal("0.01"),
                    volumeMin = BigDecimal("0.01"),
                    volumeMax = null,
                    pointSize = BigDecimal("0.001"),
                    digits = 3,
                    tradeStopsLevelPoints = 0,
                )
        }

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { events += it }
        bus.subscribe<BrokerEvent.OrderCancelled> { events += it }
    }

    private fun sim() =
        MT5BrokerSimulator(bus, clock, MarketPriceTracker(), registry, syntheticSpreadPoints = 0, stopLatencyMs = 250L)

    private fun quote(
        ms: Long,
        price: String,
    ) {
        clock.time = ms
        bus.publish(TickEvent(Tick(symbol, Money.of(price), ms, bid = Money.of(price), ask = Money.of(price))))
    }

    private fun touch(
        id: String,
        onTrigger: TriggerType,
    ) = OrderRequest.IfTouched(
        id = id,
        symbol = symbol,
        side = Side.SELL,
        quantity = Money.of("0.01"),
        triggerPrice = Money.of("2010"),
        onTrigger = onTrigger,
        limitPrice = if (onTrigger == TriggerType.LIMIT) Money.of("2009") else null,
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        legIntent = LegIntent.Close(legId = "leg-1"),
    )

    @Test
    fun `a negative stop latency is refused at construction`() {
        assertThatThrownBy {
            MT5BrokerSimulator(bus, clock, MarketPriceTracker(), registry, stopLatencyMs = -1L)
        }.hasMessage("stopLatencyMs must be >= 0: -1")
    }

    @Test
    fun `a protective if-touched market exit waits out the stop latency`() {
        val broker = sim()
        quote(0L, "2000")
        broker.submit(touch("tp", TriggerType.MARKET))

        quote(1_000L, "2010.5")
        assertThat(events).isEmpty()
        quote(1_300L, "2011")

        val fill = events.single() as BrokerEvent.OrderFilled
        assertThat(fill.clientOrderId).isEqualTo("tp")
        assertThat(fill.price).isEqualByComparingTo("2011")
    }

    @Test
    fun `a protective if-touched limit exit is not delayed`() {
        val broker = sim()
        quote(0L, "2000")
        broker.submit(touch("tpl", TriggerType.LIMIT))

        quote(1_000L, "2010.5")

        assertThat((events.single() as BrokerEvent.OrderFilled).price).isEqualByComparingTo("2010.5")
    }

    @Test
    fun `cancelling an order the venue does not hold publishes nothing`() {
        sim().cancel("ghost")

        assertThat(events).isEmpty()
    }
}
