package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MT5BrokerSimulatorOrderSpacingTest {
    private val clock = FixedClock(0L)
    private val bus = EventBus(clock, MonotonicSequenceGenerator())
    private val tracker = MarketPriceTracker()
    private val fills = mutableListOf<BrokerEvent.OrderFilled>()

    init {
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
    }

    private fun sim(
        latencyMs: Long = 0L,
        orderSpacingMs: Long = 0L,
    ) = MT5BrokerSimulator(
        bus,
        clock,
        tracker,
        registry,
        latencyMs = latencyMs,
        orderSpacingMs = orderSpacingMs,
    )

    private fun quote(
        at: Long,
        ask: String,
    ) {
        clock.advanceTo(at)
        val askPrice = Money.of(ask)
        val bid = askPrice.subtract(BigDecimal("0.260"))
        val tick =
            Tick(
                symbol = SYMBOL,
                price = askPrice.subtract(BigDecimal("0.130")),
                timestamp = at,
                bid = bid,
                ask = askPrice,
            )
        tracker.update(tick.symbol, tick.price)
        bus.publish(TickEvent(tick))
    }

    private fun buy(id: String) =
        OrderRequest.Market(
            id = id,
            symbol = SYMBOL,
            side = Side.BUY,
            quantity = Money.of("0.05"),
            timeInForce = TimeInForce.GTC,
            timestamp = clock.now(),
        )

    private fun filledAt(): List<Pair<String, BigDecimal>> = fills.map { it.clientOrderId to it.price }

    @Test
    fun `a burst on one lane releases each order a spacing apart at the ask quoted then`() {
        val sim = sim(orderSpacingMs = 150L)
        quote(0L, "2000.100")
        listOf("leg0", "leg1", "leg2").forEach { sim.submit(buy(it)) }
        assertThat(filledAt().map { it.first }).containsExactly("leg0")

        quote(100L, "2000.200")
        assertThat(fills).hasSize(1)
        quote(150L, "2000.300")
        quote(300L, "2000.500")

        assertThat(filledAt().map { it.first }).containsExactly("leg0", "leg1", "leg2")
        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.100"), Money.of("2000.300"), Money.of("2000.500"))
    }

    @Test
    fun `zero spacing fills the whole burst on the submit tick`() {
        val sim = sim()
        quote(0L, "2000.100")
        listOf("leg0", "leg1", "leg2").forEach { sim.submit(buy(it)) }

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.100"), Money.of("2000.100"), Money.of("2000.100"))
    }

    @Test
    fun `latency and spacing compose as the later of submit plus latency and previous release plus spacing`() {
        val sim = sim(latencyMs = 100L, orderSpacingMs = 150L)
        quote(0L, "2000.100")
        listOf("leg0", "leg1", "leg2").forEach { sim.submit(buy(it)) }
        quote(100L, "2000.200")
        quote(250L, "2000.300")
        quote(400L, "2000.400")
        quote(1_000L, "2000.900")
        sim.submit(buy("late"))
        quote(1_050L, "2001.000")
        quote(1_100L, "2001.100")

        assertThat(filledAt().map { it.first }).containsExactly("leg0", "leg1", "leg2", "late")
        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.200"), Money.of("2000.300"), Money.of("2000.400"), Money.of("2001.100"))
    }

    @Test
    fun `orders released inside one tick gap fill at the quote prevailing at their release`() {
        val sim = sim(orderSpacingMs = 150L)
        quote(0L, "2000.100")
        listOf("leg0", "leg1", "leg2").forEach { sim.submit(buy(it)) }
        quote(1_000L, "2000.700")

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.100"), Money.of("2000.100"), Money.of("2000.100"))
    }

    @Test
    fun `a delayed buy fills at the ask of the last tick before its release, not the next tick`() {
        val sim = sim(latencyMs = 100L)
        quote(0L, "2000.100")
        sim.submit(buy("leg0"))
        quote(60L, "2000.200")
        assertThat(fills).isEmpty()
        quote(180L, "2000.900")

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.200"))
    }

    @Test
    fun `a delayed sell fills at the bid of the last tick before its release`() {
        val sim = sim(latencyMs = 100L)
        quote(0L, "2000.100")
        sim.submit(
            buy("leg0").let {
                OrderRequest.Market(it.id, it.symbol, Side.SELL, it.quantity, it.timeInForce, it.timestamp)
            },
        )
        quote(60L, "2000.200")
        quote(180L, "2000.900")

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("1999.940"))
    }

    @Test
    fun `a release that lands exactly on a tick fills at that tick`() {
        val sim = sim(latencyMs = 100L)
        quote(0L, "2000.100")
        sim.submit(buy("leg0"))
        quote(100L, "2000.400")

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.400"))
    }

    @Test
    fun `lane and latency releases between sparse ticks each take the quote prevailing then`() {
        val sim = sim(latencyMs = 100L, orderSpacingMs = 150L)
        quote(0L, "2000.100")
        listOf("leg0", "leg1", "leg2").forEach { sim.submit(buy(it)) }
        quote(50L, "2000.200")
        quote(200L, "2000.300")
        quote(900L, "2000.800")

        assertThat(fills.map { it.price })
            .usingElementComparator(BigDecimal::compareTo)
            .containsExactly(Money.of("2000.200"), Money.of("2000.300"), Money.of("2000.300"))
    }

    private companion object {
        const val SYMBOL = "EXNESS:XAUUSD"
        val registry =
            object : InstrumentRegistry {
                override fun lookup(qktSymbol: String): InstrumentMeta? =
                    if (qktSymbol != SYMBOL) {
                        null
                    } else {
                        InstrumentMeta(
                            qktSymbol = SYMBOL,
                            contractSize = BigDecimal("100"),
                            volumeStep = BigDecimal("0.01"),
                            volumeMin = BigDecimal("0.01"),
                            volumeMax = null,
                            pointSize = BigDecimal("0.001"),
                            digits = 3,
                            tradeStopsLevelPoints = 0,
                        )
                    }
            }
    }
}
