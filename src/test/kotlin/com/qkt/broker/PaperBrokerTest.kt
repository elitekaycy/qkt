package com.qkt.broker

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
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

class PaperBrokerTest {
    private fun newBus(): EventBus = EventBus(FixedClock(0L), MonotonicSequenceGenerator())

    @Test
    fun `position accounting mode is the configured venue model, defaulting to netting`() {
        val bus = newBus()
        val default = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        assertThat(default.positionAccountingMode("X")).isEqualTo(PositionAccountingMode.NETTING)
        val hedging =
            PaperBroker(
                bus,
                FixedClock(0L),
                MarketPriceTracker(),
                positionMode = PositionAccountingMode.HEDGING,
            )
        assertThat(hedging.positionAccountingMode("X")).isEqualTo(PositionAccountingMode.HEDGING)
    }

    private fun tick(
        symbol: String,
        price: String,
        ts: Long = 1L,
    ) = Tick(symbol, Money.of(price), ts)

    private fun instrumentRegistry(
        volumeStep: String,
        volumeMin: String,
    ): InstrumentRegistry =
        object : InstrumentRegistry {
            override fun lookup(qktSymbol: String): InstrumentMeta =
                InstrumentMeta(
                    qktSymbol = qktSymbol,
                    contractSize = BigDecimal.ONE,
                    volumeStep = BigDecimal(volumeStep),
                    volumeMin = BigDecimal(volumeMin),
                    volumeMax = null,
                    pointSize = BigDecimal("0.01"),
                    digits = 2,
                    tradeStopsLevelPoints = 0,
                )
        }

    @Test
    fun `volume is quantized down to instrument step before fill`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2000"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val broker =
            PaperBroker(
                bus,
                FixedClock(0L),
                tracker,
                instrumentRegistry(volumeStep = "0.1", volumeMin = "0.1"),
            )

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "quantized",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.19"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isTrue()
        assertThat(fills.single().quantity).isEqualByComparingTo("0.1")
    }

    @Test
    fun `volume below instrument minimum is rejected without a fill`() {
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("2000"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val rejections = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        bus.subscribe<BrokerEvent.OrderRejected> { rejections.add(it) }
        val broker =
            PaperBroker(
                bus,
                FixedClock(0L),
                tracker,
                instrumentRegistry(volumeStep = "0.1", volumeMin = "0.2"),
            )

        val ack =
            broker.submit(
                OrderRequest.Market(
                    id = "too-small",
                    symbol = "XAUUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.19"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                ),
            )

        assertThat(ack.accepted).isFalse()
        assertThat(ack.rejectReason).contains("below venue volumeMin 0.2")
        assertThat(rejections.single().reason).contains("quantized volume 0.1")
        assertThat(fills).isEmpty()
    }

    @Test
    fun `Market fills at last tracker price`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        val req =
            OrderRequest.Market(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            )
        b.submit(req)

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.10"))
        assertThat(fills.single().quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `Market with no tracker price emits OrderRejected`() {
        val bus = newBus()
        val rejects = mutableListOf<BrokerEvent.OrderRejected>()
        bus.subscribe<BrokerEvent.OrderRejected> { e -> rejects.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())

        b.submit(
            OrderRequest.Market(
                id = "c1",
                symbol = "UNKNOWN",
                side = Side.BUY,
                quantity = Money.of("1"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(rejects).hasSize(1)
    }

    @Test
    fun `Limit fills when a tick crosses the limit price`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        assertThat(fills).isEmpty()

        b.onTick(tick("EURUSD", "1.105"))
        assertThat(fills).isEmpty()

        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.099"))
    }

    @Test
    fun `IOC and FOK cancel immediately when the current quote cannot fill`() {
        for (tif in listOf(TimeInForce.IOC, TimeInForce.FOK)) {
            val tracker = MarketPriceTracker()
            tracker.update("EURUSD", Money.of("1.10"))
            val clock = FixedClock(0L)
            val bus = EventBus(clock, MonotonicSequenceGenerator())
            val fills = mutableListOf<BrokerEvent.OrderFilled>()
            val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
            bus.subscribe<BrokerEvent.OrderFilled>(fills::add)
            bus.subscribe<BrokerEvent.OrderCancelled>(cancellations::add)
            val broker = PaperBroker(bus, clock, tracker)

            broker.submit(
                OrderRequest.Limit(
                    id = tif.name,
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("1"),
                    limitPrice = Money.of("1.09"),
                    timeInForce = tif,
                    timestamp = 0L,
                    strategyId = "owner",
                ),
            )
            broker.onTick(tick("EURUSD", "1.08"))

            assertThat(fills).isEmpty()
            assertThat(cancellations.single().reason).isEqualTo("$tif unfilled")
            assertThat(cancellations.single().strategyId).isEqualTo("owner")
        }
    }

    @Test
    fun `marketable IOC fills completely at the current paper quote`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val clock = FixedClock(0L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled>(fills::add)
        bus.subscribe<BrokerEvent.OrderCancelled>(cancellations::add)
        val broker = PaperBroker(bus, clock, tracker)

        broker.submit(
            OrderRequest.Limit(
                id = "ioc-fill",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.11"),
                timeInForce = TimeInForce.IOC,
                timestamp = 0L,
            ),
        )

        assertThat(fills.single().price).isEqualByComparingTo("1.10")
        assertThat(cancellations).isEmpty()
    }

    @Test
    fun `DAY pending order expires at the bound calendar session end`() {
        val dayEnd = 86_400_000L
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val clock = FixedClock(dayEnd - 60_000L)
        val bus = EventBus(clock, MonotonicSequenceGenerator())
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancellations = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled>(fills::add)
        bus.subscribe<BrokerEvent.OrderCancelled>(cancellations::add)
        val broker = PaperBroker(bus, clock, tracker)

        broker.submit(
            OrderRequest.Limit(
                id = "day",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.09"),
                timeInForce = TimeInForce.DAY,
                timestamp = clock.now(),
                strategyId = "owner",
            ),
        )
        clock.time = dayEnd
        broker.onTick(tick("EURUSD", "1.10", dayEnd))
        broker.onTick(tick("EURUSD", "1.08", dayEnd + 1L))

        assertThat(fills).isEmpty()
        assertThat(cancellations.single().reason).isEqualTo("DAY expired")
        assertThat(cancellations.single().strategyId).isEqualTo("owner")
    }

    @Test
    fun `Stop converts to Market on trigger`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "c1",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(tick("EURUSD", "1.085"))
        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `order cancelled by an earlier fill in the same tick is not filled`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val broker = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        bus.subscribe<BrokerEvent.OrderFilled> { fill ->
            fills.add(fill)
            if (fill.clientOrderId == "first") broker.cancel("sibling")
        }
        broker.submit(
            OrderRequest.Limit(
                id = "first",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("101"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        broker.submit(
            OrderRequest.Limit(
                id = "sibling",
                symbol = "X",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("99"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        broker.onTick(tick("X", "100"))

        assertThat(fills.map { it.clientOrderId }).containsExactly("first")
    }

    @Test
    fun `stop triggers are side-aware on the quote, not the mid`() {
        // MT5 fires BUY_STOP on the ASK and SELL_STOP on the BID. With bid 100.4 /
        // ask 100.6 (mid 100.5): a buy stop at 100.55 must trigger (ask crossed it)
        // even though the mid never did, and a sell stop at 100.55 must NOT trigger
        // (the bid never reached it) even though the ask did.
        val tracker = MarketPriceTracker()
        tracker.update("XAUUSD", Money.of("100.5"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "buy-stop",
                symbol = "XAUUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100.55"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.submit(
            OrderRequest.Stop(
                id = "sell-stop",
                symbol = "XAUUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("100.45"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(
            Tick(
                symbol = "XAUUSD",
                price = Money.of("100.5"),
                timestamp = 1L,
                bid = Money.of("100.48"),
                ask = Money.of("100.56"),
            ),
        )

        // Ask 100.56 crossed the 100.55 buy stop; bid 100.48 never reached the
        // 100.45 sell stop — even though the mid (100.5) would have triggered neither
        // or, with a wider spread, both.
        assertThat(fills.map { it.clientOrderId }).containsExactly("buy-stop")
    }

    @Test
    fun `bar-mode fills a triggered Stop at the stop price, not the overshooting tick`() {
        // A bar's synthetic low overshoots the stop; bar mode fills at the stop level
        // (no slippage), not at the bar low the tick carries.
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker, fillAtTriggerPrice = true)

        b.submit(
            OrderRequest.Stop(
                id = "sl",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.085"))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.09"))
    }

    @Test
    fun `bar-mode stop that gaps through fills at the adverse opening price`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val broker = PaperBroker(bus, FixedClock(0L), tracker, fillAtTriggerPrice = true)
        broker.onTick(tick("EURUSD", "1.10", ts = 999L))
        broker.submit(
            OrderRequest.Stop(
                id = "gap-sl",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        broker.onTick(tick("EURUSD", "1.07", ts = 10_000L))

        assertThat(fills.single().price).isEqualByComparingTo("1.07")
    }

    @Test
    fun `bar-mode fills a triggered Limit at the limit price, not the overshooting tick`() {
        // A long take-profit is a SELL limit above; the bar's synthetic high overshoots
        // it. Bar mode books the limit level, not the bar high.
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker, fillAtTriggerPrice = true)

        b.submit(
            OrderRequest.Limit(
                id = "tp",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.115"))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.11"))
    }

    @Test
    fun `stop-limit activation rests the limit until it becomes marketable`() {
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { fills.add(it) }
        val broker = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        broker.submit(
            OrderRequest.StopLimit(
                id = "stop-limit",
                symbol = "X",
                side = Side.BUY,
                quantity = Money.of("1"),
                stopPrice = Money.of("100"),
                limitPrice = Money.of("99"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        broker.onTick(tick("X", "100.5", ts = 1L))
        assertThat(fills).isEmpty()
        broker.onTick(tick("X", "98.5", ts = 2L))

        assertThat(fills.single().price).isLessThanOrEqualTo(Money.of("99"))
    }

    @Test
    fun `default tick mode still fills a triggered Stop at the printing tick`() {
        // Guard: the live/tick path is unchanged — fills at the crossing tick price.
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Stop(
                id = "sl",
                symbol = "EURUSD",
                side = Side.SELL,
                quantity = Money.of("1"),
                stopPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.085"))

        assertThat(fills).hasSize(1)
        assertThat(fills.single().price).isEqualByComparingTo(Money.of("1.085"))
    }

    @Test
    fun `cancel removes a working Limit before fill`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        val cancels = mutableListOf<BrokerEvent.OrderCancelled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> cancels.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.cancel("c1")

        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).isEmpty()
        assertThat(cancels).hasSize(1)
    }

    @Test
    fun `IfTouched MARKET fires when tick reaches trigger`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.IfTouched(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                triggerPrice = Money.of("1.10"),
                onTrigger = TriggerType.MARKET,
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))
        assertThat(fills).hasSize(1)
    }

    @Test
    fun `multiple Limits per symbol fill in submission order when a single tick crosses both`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        b.submit(
            OrderRequest.Limit(
                id = "c1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.10"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.submit(
            OrderRequest.Limit(
                id = "c2",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("1"),
                limitPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        b.onTick(tick("EURUSD", "1.099"))

        assertThat(fills.map { it.clientOrderId }).containsExactly("c1", "c2")
    }

    @Test
    fun `declares MULTI_POSITION_PER_SYMBOL capability`() {
        val bus = newBus()
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        assertThat(b.capabilities).contains(OrderTypeCapability.MULTI_POSITION_PER_SYMBOL)
    }

    @Test
    fun `does not advertise POSITION_MODIFY so bracket exits use the resting-order fallback`() {
        val bus = newBus()
        val b = PaperBroker(bus, FixedClock(0L), MarketPriceTracker())
        assertThat(b.capabilities).doesNotContain(OrderTypeCapability.POSITION_MODIFY)
    }

    @Test
    fun `multiple market fills on the same symbol each emit a distinct OrderFilled`() {
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        for (idx in 0..2) {
            b.submit(
                OrderRequest.Market(
                    id = "c$idx",
                    symbol = "EURUSD",
                    side = Side.BUY,
                    quantity = Money.of("0.1"),
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                    strategyId = "alpha",
                ),
            )
        }

        // PaperBroker does not net — each submit produces its own fill with its own id.
        assertThat(fills.map { it.clientOrderId }).containsExactly("c0", "c1", "c2")
        assertThat(fills.map { it.quantity }).allMatch { it.compareTo(Money.of("0.1")) == 0 }
    }

    @Test
    fun `concurrent limit + stop on the same symbol fire independently as price prints`() {
        val tracker = MarketPriceTracker()
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        // BUY-limit at 1.09 (fires when price ≤ 1.09)
        b.submit(
            OrderRequest.Limit(
                id = "limit-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.1"),
                limitPrice = Money.of("1.09"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        // BUY-stop at 1.11 (fires when price ≥ 1.11)
        b.submit(
            OrderRequest.Stop(
                id = "stop-1",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.1"),
                stopPrice = Money.of("1.11"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        b.onTick(tick("EURUSD", "1.12")) // crosses the stop only
        assertThat(fills.map { it.clientOrderId }).containsExactly("stop-1")
        b.onTick(tick("EURUSD", "1.08")) // crosses the limit
        assertThat(fills.map { it.clientOrderId }).containsExactly("stop-1", "limit-1")
    }

    @Test
    fun `stack-shape multi-fill on same symbol does not commingle quantities`() {
        // Mirrors the STACK_AT stack-entry market shape: each tier's entry comes through
        // with its own client id and its own qty. PaperBroker treats them as independent.
        val tracker = MarketPriceTracker()
        tracker.update("EURUSD", Money.of("1.10"))
        val bus = newBus()
        val fills = mutableListOf<BrokerEvent.OrderFilled>()
        bus.subscribe<BrokerEvent.OrderFilled> { e -> fills.add(e) }
        val b = PaperBroker(bus, FixedClock(0L), tracker)

        // Primary entry
        b.submit(
            OrderRequest.Market(
                id = "primary-1-entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.20"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )
        // Stack tier-0 entry at a later (favorable) price
        tracker.update("EURUSD", Money.of("1.106"))
        b.submit(
            OrderRequest.Market(
                id = "primary-1-stack-tier0-entry",
                symbol = "EURUSD",
                side = Side.BUY,
                quantity = Money.of("0.06"),
                timeInForce = TimeInForce.GTC,
                timestamp = 0L,
            ),
        )

        assertThat(fills).hasSize(2)
        assertThat(fills[0].clientOrderId).isEqualTo("primary-1-entry")
        assertThat(fills[0].quantity).isEqualByComparingTo("0.20")
        assertThat(fills[0].price).isEqualByComparingTo("1.10")
        assertThat(fills[1].clientOrderId).isEqualTo("primary-1-stack-tier0-entry")
        assertThat(fills[1].quantity).isEqualByComparingTo("0.06")
        assertThat(fills[1].price).isEqualByComparingTo("1.106")
    }
}
