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

class MT5BrokerSimulatorTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    private fun registry(meta: InstrumentMeta): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == meta.qktSymbol) meta else null
        }

    private fun xauusd(
        volumeStep: String = "0.01",
        volumeMin: String = "0.01",
        digits: Int = 3,
    ) = InstrumentMeta(
        qktSymbol = "EXNESS:XAUUSD",
        contractSize = BigDecimal("100"),
        volumeStep = BigDecimal(volumeStep),
        volumeMin = BigDecimal(volumeMin),
        volumeMax = null,
        pointSize = BigDecimal("0.001"),
        digits = digits,
        tradeStopsLevelPoints = 0,
    )

    private fun marketBuy(
        symbol: String,
        qty: String,
    ) = OrderRequest.Market(
        id = "c1",
        symbol = symbol,
        side = Side.BUY,
        quantity = Money.of(qty),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
    )

    @Test
    fun `volume is quantized DOWN to volumeStep on fill`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        tracker.update("EXNESS:XAUUSD", Money.of("2000.000"))
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), tracker, registry(xauusd()))

        // 0.157 lots → quantized DOWN to 0.15 (step = 0.01)
        sim.submit(marketBuy("EXNESS:XAUUSD", "0.157"))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("0.15"))
    }

    @Test
    fun `order below volumeMin is rejected and not filled`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
        val tracker = MarketPriceTracker()
        tracker.update("EXNESS:XAUUSD", Money.of("2000.000"))
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd(volumeStep = "0.01", volumeMin = "0.10")),
            )

        // 0.05 lots — quantizes to 0.05, but volumeMin is 0.10.
        sim.submit(marketBuy("EXNESS:XAUUSD", "0.05"))

        assertThat(fills).isEmpty()
        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("below venue volumeMin")
    }

    @Test
    fun `missing InstrumentMeta rejects the order`() {
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                MarketPriceTracker(),
                object : InstrumentRegistry {
                    override fun lookup(qktSymbol: String): InstrumentMeta? = null
                },
            )

        sim.submit(marketBuy("UNKNOWN", "1"))

        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("no InstrumentMeta")
    }

    @Test
    fun `fill price is rounded HALF_EVEN to digits`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        tracker.update("EXNESS:XAUUSD", Money.of("2000.0005"))
        // No bid/ask on the last-tracker price; with syntheticSpreadPoints=0 we get pure mid.
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd(digits = 2)),
                syntheticSpreadPoints = 0,
            )

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(fills).hasSize(1)
        // Rounded HALF_EVEN to 2 digits: 2000.0005 → 2000.00 (banker's rounding).
        // Then re-scaled to Money.SCALE (8) for storage.
        assertThat(fills.single().price.setScale(2, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo(Money.of("2000.00"))
    }

    @Test
    fun `market BUY fills at ask, SELL fills at bid when tick has bid and ask`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), tracker, registry(xauusd()))

        // Publish a tick with explicit bid/ask before the order.
        val tick =
            Tick(
                symbol = "EXNESS:XAUUSD",
                price = Money.of("2000.000"),
                timestamp = 0L,
                bid = Money.of("1999.950"),
                ask = Money.of("2000.050"),
            )
        bus.publish(TickEvent(tick))
        tracker.update("EXNESS:XAUUSD", tick.price)

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))
        sim.submit(
            OrderRequest.Market(
                id = "c2",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(fills).hasSize(2)
        assertThat(fills[0].side).isEqualTo(Side.BUY)
        assertThat(fills[0].price).isEqualByComparingTo(Money.of("2000.050"))
        assertThat(fills[1].side).isEqualTo(Side.SELL)
        assertThat(fills[1].price).isEqualByComparingTo(Money.of("1999.950"))
    }

    @Test
    fun `synthetic spread applied when tick has no bid or ask`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        // syntheticSpreadPoints = 4 → half-spread = 4*0.001/2 = 0.002.
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd()),
                syntheticSpreadPoints = 4,
            )
        val tick = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2000.000"), timestamp = 0L)
        bus.publish(TickEvent(tick))
        tracker.update("EXNESS:XAUUSD", tick.price)

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(fills).hasSize(1)
        // BUY at mid+halfSpread = 2000.000 + 0.002 = 2000.002, rounded to digits=3.
        assertThat(fills.single().price.setScale(3, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo(Money.of("2000.002"))
    }

    @Test
    fun `bracket limit fills at ask on trigger for BUY`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))

        // Queue a limit BUY at 2000.000.
        sim.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("2000.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()

        // Tick crosses the limit at 1999.900 with bid=1999.850, ask=1999.950.
        val tick =
            Tick(
                symbol = "EXNESS:XAUUSD",
                price = Money.of("1999.900"),
                timestamp = 1L,
                bid = Money.of("1999.850"),
                ask = Money.of("1999.950"),
            )
        bus.publish(TickEvent(tick))

        assertThat(fills).hasSize(1)
        // BUY fills at ask, not the limit price.
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1999.950"))
    }

    @Test
    fun `FixedPointsSlippage shifts fill price adverse to side`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd()),
                slippage = FixedPointsSlippage(points = 3),
                syntheticSpreadPoints = 0,
            )
        val tick = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2000.000"), timestamp = 0L)
        bus.publish(TickEvent(tick))
        tracker.update("EXNESS:XAUUSD", tick.price)

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(fills).hasSize(1)
        // BUY shifted UP by 3 * pointSize (0.001) = +0.003.
        assertThat(fills.single().price.setScale(3, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo(Money.of("2000.003"))
    }
}
