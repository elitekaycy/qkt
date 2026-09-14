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
import org.junit.jupiter.api.Test

class MT5BrokerSimulatorTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `position accounting mode defaults to hedging like the real retail venue`() {
        val bus = newBus()
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))
        assertThat(sim.positionAccountingMode("EXNESS:XAUUSD")).isEqualTo(PositionAccountingMode.HEDGING)
        val netting =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                MarketPriceTracker(),
                registry(xauusd()),
                positionMode = PositionAccountingMode.NETTING,
            )
        assertThat(netting.positionAccountingMode("EXNESS:XAUUSD")).isEqualTo(PositionAccountingMode.NETTING)
    }

    private fun registry(meta: InstrumentMeta): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta? = if (qktSymbol == meta.qktSymbol) meta else null
        }

    private fun xauusd(
        volumeStep: String = "0.01",
        volumeMin: String = "0.01",
        volumeMax: String? = null,
        digits: Int = 3,
        tradeStopsLevelPoints: Int = 0,
    ) = InstrumentMeta(
        qktSymbol = "EXNESS:XAUUSD",
        contractSize = BigDecimal("100"),
        volumeStep = BigDecimal(volumeStep),
        volumeMin = BigDecimal(volumeMin),
        volumeMax = volumeMax?.let(::BigDecimal),
        pointSize = BigDecimal("0.001"),
        digits = digits,
        tradeStopsLevelPoints = tradeStopsLevelPoints,
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
    fun `order above volumeMax is rejected and not filled`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
        val tracker = MarketPriceTracker()
        tracker.update("EXNESS:XAUUSD", Money.of("2000.000"))
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), tracker, registry(xauusd(volumeMax = "2.00")))

        sim.submit(marketBuy("EXNESS:XAUUSD", "2.01"))

        assertThat(fills).isEmpty()
        assertThat(rejects.single().reason).contains("above venue volumeMax 2.00")
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
    fun `order cancelled by an earlier fill in the same tick is not filled`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val symbol = "EXNESS:XAUUSD"
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))
        bus.subscribe<BrokerEvent.OrderFilled> { fill ->
            fills.add(fill)
            if (fill.clientOrderId == "first") sim.cancel("sibling")
        }
        sim.submit(
            OrderRequest.Limit(
                id = "first",
                symbol = symbol,
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("2001"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        sim.submit(
            OrderRequest.Limit(
                id = "sibling",
                symbol = symbol,
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("1999"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        sim.onTick(Tick(symbol, Money.of("2000"), 1L))

        assertThat(fills.map { it.clientOrderId }).containsExactly("first")
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
    fun `fixed event-time latency fills market order from a later tick`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                tracker,
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                latencyMs = 1_000L,
            )

        clock.advanceTo(0L)
        val first = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2000.000"), timestamp = 0L)
        tracker.update(first.symbol, first.price)
        bus.publish(TickEvent(first))
        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))
        assertThat(fills).isEmpty()

        clock.advanceTo(500L)
        val early = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2001.000"), timestamp = 500L)
        tracker.update(early.symbol, early.price)
        bus.publish(TickEvent(early))
        assertThat(fills).isEmpty()

        clock.advanceTo(1_000L)
        val due = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2002.000"), timestamp = 1_000L)
        tracker.update(due.symbol, due.price)
        bus.publish(TickEvent(due))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("2002.000"))
    }

    @Test
    fun `trade stops level rejects pending order too close to current price`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
        val tracker = MarketPriceTracker()
        tracker.update("EXNESS:XAUUSD", Money.of("2000.000"))
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                tracker,
                registry(xauusd(tradeStopsLevelPoints = 100)),
                syntheticSpreadPoints = 0,
                enforceStopsLevel = true,
            )

        val ack =
            sim.submit(
                OrderRequest.Stop(
                    id = "too-close",
                    symbol = "EXNESS:XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.01"),
                    stopPrice = Money.of("2000.050"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("tradeStopsLevel")
    }

    @Test
    fun `stop gap-through fills at the adverse printed price`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                tracker,
                registry(xauusd()),
                syntheticSpreadPoints = 0,
            )
        sim.submit(
            OrderRequest.Stop(
                id = "gap-stop",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("1999.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        clock.advanceTo(1_000L)
        val gap = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("1995.000"), timestamp = 1_000L)
        tracker.update(gap.symbol, gap.price)
        bus.publish(TickEvent(gap))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1995.000"))
    }

    @Test
    fun `stop-limit activation rests the limit until it becomes marketable`() {
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
                syntheticSpreadPoints = 0,
            )
        sim.submit(
            OrderRequest.StopLimit(
                id = "stop-limit",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("2000"),
                limitPrice = Money.of("1999"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        sim.onTick(Tick("EXNESS:XAUUSD", Money.of("2000.5"), 1L))
        assertThat(fills).isEmpty()
        sim.onTick(Tick("EXNESS:XAUUSD", Money.of("1998.5"), 2L))

        assertThat(fills.single().price).isLessThanOrEqualTo(Money.of("1999"))
    }

    @Test
    fun `limit fill never applies adverse slippage beyond its limit`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                MarketPriceTracker(),
                registry(xauusd()),
                slippage = FixedPointsSlippage(points = 10),
                syntheticSpreadPoints = 0,
            )
        sim.submit(
            OrderRequest.Limit(
                id = "buy-limit",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("2000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        sim.onTick(Tick("EXNESS:XAUUSD", Money.of("1999.5"), 1L))

        assertThat(fills.single().price).isLessThanOrEqualTo(Money.of("2000"))
    }

    @Test
    fun `deterministic rejection model rejects configured order ordinal`() {
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { rejects.add(it) }
        val tracker = MarketPriceTracker().apply { update("EXNESS:XAUUSD", Money.of("2000.000")) }
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd()),
                rejectionModel = RejectEveryNthOrder(1),
            )

        val ack = sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(ack.accepted).isFalse()
        assertThat(rejects).hasSize(1)
        assertThat(rejects.single().reason).contains("simulated deterministic rejection")
    }

    @Test
    fun `fractional partial fill emits a partial slice and final remainder`() {
        val bus = newBus()
        val partials = mutableListOf<BrokerEvent.OrderPartiallyFilled>()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { partials.add(it) }
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker().apply { update("EXNESS:XAUUSD", Money.of("2000.000")) }
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                partialFillModel = FractionalPartialFill(BigDecimal("0.50")),
            )

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.10"))

        assertThat(partials).hasSize(1)
        assertThat(partials.single().quantity).isEqualByComparingTo(Money.of("0.05"))
        assertThat(partials.single().cumulativeFilled).isEqualByComparingTo(Money.of("0.05"))
        assertThat(fills).hasSize(1)
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("0.05"))
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

    @Test
    fun `InstrumentSlippage shifts fill by the instrument's slippagePoints`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim =
            MT5BrokerSimulator(
                bus,
                FixedClock(0L),
                tracker,
                registry(xauusd().copy(slippagePoints = 5)),
                slippage = InstrumentSlippage,
                syntheticSpreadPoints = 0,
            )
        val tick = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2000.000"), timestamp = 0L)
        bus.publish(TickEvent(tick))
        tracker.update("EXNESS:XAUUSD", tick.price)

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(fills).hasSize(1)
        // BUY shifted UP by 5 * pointSize (0.001) = +0.005.
        assertThat(fills.single().price.setScale(3, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo(Money.of("2000.005"))
    }

    @Test
    fun `InstrumentSlippage is a no-op when slippagePoints is zero`() {
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
                slippage = InstrumentSlippage,
                syntheticSpreadPoints = 0,
            )
        val tick = Tick(symbol = "EXNESS:XAUUSD", price = Money.of("2000.000"), timestamp = 0L)
        bus.publish(TickEvent(tick))
        tracker.update("EXNESS:XAUUSD", tick.price)

        sim.submit(marketBuy("EXNESS:XAUUSD", "0.01"))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price.setScale(3, java.math.RoundingMode.HALF_EVEN))
            .isEqualByComparingTo(Money.of("2000.000"))
    }

    @Test
    fun `cancel removes a working order and publishes OrderCancelled`() {
        val bus = newBus()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))

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

        sim.cancel("c1")

        assertThat(cancels).hasSize(1)
        assertThat(cancels.single().clientOrderId).isEqualTo("c1")
    }

    @Test
    fun `Stop BUY triggers and fills at ask when tick crosses stop price`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))

        sim.submit(
            OrderRequest.Stop(
                id = "c1",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("2010.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()

        val tick =
            Tick(
                symbol = "EXNESS:XAUUSD",
                price = Money.of("2010.100"),
                timestamp = 1L,
                bid = Money.of("2010.050"),
                ask = Money.of("2010.150"),
            )
        bus.publish(TickEvent(tick))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("2010.150"))
    }

    @Test
    fun `StopLimit BUY activates then fills at limit or better`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))

        sim.submit(
            OrderRequest.StopLimit(
                id = "c1",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("2010.000"),
                limitPrice = Money.of("2010.200"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(
            TickEvent(
                Tick(
                    symbol = "EXNESS:XAUUSD",
                    price = Money.of("2010.500"),
                    timestamp = 1L,
                    bid = Money.of("2010.450"),
                    ask = Money.of("2010.550"),
                ),
            ),
        )

        assertThat(fills).isEmpty()
        bus.publish(
            TickEvent(
                Tick(
                    symbol = "EXNESS:XAUUSD",
                    price = Money.of("2010.100"),
                    timestamp = 2L,
                    bid = Money.of("2010.050"),
                    ask = Money.of("2010.150"),
                ),
            ),
        )

        assertThat(fills.single().price).isEqualByComparingTo(Money.of("2010.150"))
    }

    @Test
    fun `IfTouched MARKET BUY triggers below and fills at ask`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim = MT5BrokerSimulator(bus, FixedClock(0L), MarketPriceTracker(), registry(xauusd()))

        sim.submit(
            OrderRequest.IfTouched(
                id = "c1",
                symbol = "EXNESS:XAUUSD",
                side = Side.BUY,
                quantity = Money.of("0.01"),
                triggerPrice = Money.of("1990.000"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        bus.publish(
            TickEvent(
                Tick(
                    symbol = "EXNESS:XAUUSD",
                    price = Money.of("1989.500"),
                    timestamp = 1L,
                    bid = Money.of("1989.450"),
                    ask = Money.of("1989.550"),
                ),
            ),
        )

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1989.550"))
    }

    private fun protectiveSellStop(
        id: String = "sl",
        stop: String = "1999.000",
    ) = OrderRequest.Stop(
        id = id,
        symbol = "EXNESS:XAUUSD",
        side = Side.SELL,
        quantity = Money.of("0.01"),
        stopPrice = Money.of(stop),
        timeInForce = TimeInForce.GTC,
        timestamp = 0L,
        legIntent = LegIntent.Close(legId = "leg-1"),
    )

    private fun quote(
        ms: Long,
        bid: String,
        ask: String,
    ) = Tick(
        symbol = "EXNESS:XAUUSD",
        price = Money.of(bid),
        timestamp = ms,
        bid = Money.of(bid),
        ask = Money.of(ask),
    )

    @Test
    fun `protective stop with stop latency fills at the first quote after the delay, not the crossing print (#1135)`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val tracker = MarketPriceTracker()
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                tracker,
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                stopLatencyMs = 300L,
            )
        sim.submit(protectiveSellStop())

        // Crossing print at t=1000: bid 1998.900 <= 1999.000 triggers, but nothing fills yet.
        clock.advanceTo(1_000L)
        bus.publish(TickEvent(quote(1_000L, "1998.900", "1999.100")))
        assertThat(fills).isEmpty()
        // A quote inside the delay is not the execution either.
        clock.advanceTo(1_200L)
        bus.publish(TickEvent(quote(1_200L, "1998.500", "1998.700")))
        assertThat(fills).isEmpty()
        // First quote at or after trigger + 300 ms executes, sided on the bid.
        clock.advanceTo(1_350L)
        bus.publish(TickEvent(quote(1_350L, "1997.800", "1998.000")))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().clientOrderId).isEqualTo("sl")
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1997.800"))
    }

    @Test
    fun `stop latency leaves entry stops and zero-delay runs on the crossing print (#1135)`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val delayed =
            MT5BrokerSimulator(
                bus,
                clock,
                MarketPriceTracker(),
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                stopLatencyMs = 300L,
            )
        // An entry stop (no Close intent) is a placement, not a protective exit: unaffected.
        delayed.submit(
            OrderRequest.Stop(
                id = "entry-stop",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                stopPrice = Money.of("1999.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        clock.advanceTo(1_000L)
        bus.publish(TickEvent(quote(1_000L, "1998.900", "1999.100")))
        assertThat(fills.map { it.clientOrderId }).containsExactly("entry-stop")
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1998.900"))

        // Default delay 0: a protective stop still fills on the crossing print (byte-identical history).
        fills.clear()
        val bus2 = EventBus(clock, MonotonicSequenceGenerator())
        bus2.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val immediate =
            MT5BrokerSimulator(bus2, clock, MarketPriceTracker(), registry(xauusd()), syntheticSpreadPoints = 0)
        immediate.submit(protectiveSellStop())
        clock.advanceTo(2_000L)
        bus2.publish(TickEvent(quote(2_000L, "1998.900", "1999.100")))
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1998.900"))
    }

    @Test
    fun `a stop cancelled during its execution delay never fills (#1135)`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderCancelled> { cancels.add(it) }
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                MarketPriceTracker(),
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                stopLatencyMs = 300L,
            )
        sim.submit(protectiveSellStop())
        clock.advanceTo(1_000L)
        bus.publish(TickEvent(quote(1_000L, "1998.900", "1999.100")))
        // The OCO sibling filled meanwhile and the engine cancels the stop.
        sim.cancel("sl")
        assertThat(cancels.map { it.clientOrderId }).containsExactly("sl")
        clock.advanceTo(2_000L)
        bus.publish(TickEvent(quote(2_000L, "1997.000", "1997.200")))
        assertThat(fills).isEmpty()
    }

    @Test
    fun `take-profit LEVEL books a gapped protective limit at its level while PRINT keeps the improvement (#1135)`() {
        fun run(mode: TakeProfitFill): BigDecimal {
            val clock = FixedClock(0L)
            val bus = EventBus(clock, MonotonicSequenceGenerator())
            val fills = mutableListOf<BrokerEvent.OrderFilled>()
            bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
            val sim =
                MT5BrokerSimulator(
                    bus,
                    clock,
                    MarketPriceTracker(),
                    registry(xauusd()),
                    syntheticSpreadPoints = 0,
                    takeProfitFill = mode,
                )
            sim.submit(
                OrderRequest.Limit(
                    id = "tp",
                    symbol = "EXNESS:XAUUSD",
                    side = Side.SELL,
                    quantity = Money.of("0.01"),
                    limitPrice = Money.of("2001.000"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                    legIntent =
                        com.qkt.execution.LegIntent
                            .Close(legId = "leg-1"),
                ),
            )
            clock.advanceTo(1_000L)
            // Gap through the level: bid prints 2002.500.
            bus.publish(TickEvent(quote(1_000L, "2002.500", "2002.700")))
            return fills.single().price
        }
        assertThat(run(TakeProfitFill.PRINT)).isEqualByComparingTo(Money.of("2002.500"))
        assertThat(run(TakeProfitFill.LEVEL)).isEqualByComparingTo(Money.of("2001.000"))
    }

    @Test
    fun `take-profit fill mode LEVEL does not touch entry limits (#1135)`() {
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val sim =
            MT5BrokerSimulator(
                bus,
                clock,
                MarketPriceTracker(),
                registry(xauusd()),
                syntheticSpreadPoints = 0,
                takeProfitFill = TakeProfitFill.LEVEL,
            )
        sim.submit(
            OrderRequest.Limit(
                id = "entry-limit",
                symbol = "EXNESS:XAUUSD",
                side = Side.SELL,
                quantity = Money.of("0.01"),
                limitPrice = Money.of("2001.000"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        clock.advanceTo(1_000L)
        bus.publish(TickEvent(quote(1_000L, "2002.500", "2002.700")))
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("2002.500"))
    }
}
