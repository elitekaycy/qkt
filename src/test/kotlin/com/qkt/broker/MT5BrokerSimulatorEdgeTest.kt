package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
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

class MT5BrokerSimulatorEdgeTest {
    private val symbol = "EXNESS:XAUUSD"
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()
    private val rejects = mutableListOf<BrokerEvent.OrderRejected>()
    private val tracker = MarketPriceTracker()
    private var metaVisible = true
    private val meta =
        InstrumentMeta(
            qktSymbol = symbol,
            contractSize = BigDecimal("100"),
            volumeStep = BigDecimal("0.01"),
            volumeMin = BigDecimal("0.01"),
            volumeMax = null,
            pointSize = BigDecimal("0.001"),
            digits = 3,
            tradeStopsLevelPoints = 100,
        )
    private val registry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = meta.takeIf { metaVisible && qktSymbol == symbol }
        }

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
    }

    private fun sim(enforceStopsLevel: Boolean = false) =
        MT5BrokerSimulator(
            bus,
            clock,
            tracker,
            registry,
            syntheticSpreadPoints = 0,
            enforceStopsLevel = enforceStopsLevel,
        )

    private fun tick(
        ms: Long,
        bid: String,
        ask: String,
    ) = bus.publish(TickEvent(Tick(symbol, Money.of(bid), ms, bid = Money.of(bid), ask = Money.of(ask))))

    private fun market(id: String = "m") =
        OrderRequest.Market(id, symbol, Side.BUY, Money.of("0.01"), timeInForce = TimeInForce.GTC, timestamp = 0L)

    private fun limit(price: String) =
        OrderRequest.Limit("entry", symbol, Side.BUY, Money.of("0.01"), BigDecimal(price), TimeInForce.GTC, 0L)

    private fun bracket(
        entry: OrderRequest,
        tp: String,
        stopLoss: StopLossSpec,
    ) = OrderRequest.Bracket(
        "b",
        symbol,
        Side.BUY,
        Money.of("0.01"),
        entry,
        BigDecimal(tp),
        stopLoss,
        TimeInForce.GTC,
        0,
    )

    private fun ifTouchedLimit(
        trigger: String,
        limit: String,
    ) = OrderRequest.IfTouched(
        id = "it",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of("0.01"),
        triggerPrice = BigDecimal(trigger),
        onTrigger = TriggerType.LIMIT,
        limitPrice = BigDecimal(limit),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `a negative synthetic spread is refused at construction`() {
        assertThatThrownBy { MT5BrokerSimulator(bus, clock, tracker, registry, syntheticSpreadPoints = -1) }
            .hasMessageContaining("syntheticSpreadPoints must be >= 0: -1")
    }

    @Test
    fun `a market order with no quote and no last price is rejected`() {
        val ack = sim().submit(market())

        assertThat(ack.accepted).isTrue()
        assertThat(fills).isEmpty()
        assertThat(rejects.single().reason).isEqualTo("no price for $symbol")
    }

    @Test
    fun `a resting order whose instrument vanished by fill time is rejected on trigger`() {
        val broker = sim()
        broker.submit(OrderRequest.Stop("s", symbol, Side.BUY, Money.of("0.01"), Money.of("2001"), TimeInForce.GTC, 0L))
        metaVisible = false

        tick(1L, "2001.500", "2001.500")

        assertThat(fills).isEmpty()
        assertThat(rejects.single().reason).isEqualTo("no InstrumentMeta for symbol $symbol at fill time")
    }

    @Test
    fun `an if-touched limit fills at once when its limit is marketable on the trigger tick`() {
        sim().submit(ifTouchedLimit(trigger = "1990.000", limit = "1990.500"))

        tick(1L, "1989.900", "1990.000")

        assertThat(fills.single().clientOrderId).isEqualTo("it")
        assertThat(fills.single().price).isEqualByComparingTo("1990.000")
    }

    @Test
    fun `an if-touched limit that is not yet marketable rests as a limit until price reaches it`() {
        sim().submit(ifTouchedLimit(trigger = "1990.000", limit = "1985.000"))

        tick(1L, "1989.900", "1990.000")
        assertThat(fills).isEmpty()

        tick(2L, "1984.000", "1984.500")
        assertThat(fills.single().price).isEqualByComparingTo("1984.500")
    }

    @Test
    fun `a stop limit whose limit is marketable on activation fills on the trigger tick`() {
        val stopLimit =
            OrderRequest.StopLimit(
                "sl",
                symbol,
                Side.BUY,
                Money.of("0.01"),
                Money.of("2010"),
                Money.of("2010.2"),
                TimeInForce.GTC,
                0L,
            )
        sim().submit(stopLimit)

        tick(1L, "2010.050", "2010.150")

        assertThat(fills.single().price).isEqualByComparingTo("2010.150")
    }

    @Test
    fun `stops level is not enforced while no reference price exists`() {
        val ack = sim(enforceStopsLevel = true).submit(limit("1999.990"))

        assertThat(ack.accepted).isTrue()
        assertThat(rejects).isEmpty()
    }

    @Test
    fun `stops level checks an if-touched limit price as well as its trigger`() {
        tracker.update(symbol, Money.of("2000.000"))

        val ack = sim(enforceStopsLevel = true).submit(ifTouchedLimit(trigger = "1990.000", limit = "1999.950"))

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).startsWith("limitPrice 1999.950 is inside tradeStopsLevel 0.100")
    }

    @Test
    fun `a bracket cannot be sized by the venue simulator and is refused before any check`() {
        tracker.update(symbol, Money.of("2000.000"))
        val broker = sim(enforceStopsLevel = true)
        val bracket = bracket(market("e"), tp = "2010.000", stopLoss = StopLossSpec.Fixed(BigDecimal("1999.950")))

        assertThatThrownBy { broker.submit(bracket) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("a simulated venue cannot size a Bracket")
        assertThat(rejects).isEmpty()
    }
}
