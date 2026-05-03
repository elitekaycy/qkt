package com.qkt.app

import com.qkt.broker.MockBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndTest {
    private val clock = FixedClock(time = 1000L)
    private val ids = SequentialIdGenerator()
    private val sequencer = MonotonicSequenceGenerator()
    private val tracker = MarketPriceTracker()
    private val bus = EventBus(clock, sequencer)
    private val broker = MockBroker(clock, tracker)
    private val engine = Engine(bus, tracker)
    private val trades = mutableListOf<Trade>()
    private val orders = mutableListOf<Order>()

    private fun wirePipeline(
        strategies: List<Strategy>,
        captureOrders: Boolean = false,
    ) {
        strategies.forEach { s ->
            bus.subscribe<TickEvent> { e ->
                s.onTick(e.tick) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                s.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val order = e.signal.toOrder(ids.next(), clock.now())
            if (captureOrders) orders.add(order)
            bus.publish(OrderEvent(order))
        }
        bus.subscribe<OrderEvent> { e ->
            broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
        }
        bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
    }

    private fun Signal.toOrder(
        id: String,
        ts: Long,
    ): Order =
        when (this) {
            is Signal.Buy -> Order(id, symbol, Side.BUY, size, OrderType.MARKET, null, ts)
            is Signal.Sell -> Order(id, symbol, Side.SELL, size, OrderType.MARKET, null, ts)
        }

    private fun buyEveryTick(symbol: String) =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                emit: (Signal) -> Unit,
            ) {
                if (tick.symbol == symbol) emit(Signal.Buy(symbol, 1.0))
            }
        }

    @Test
    fun `single strategy buy on every tick produces a fill`() {
        wirePipeline(listOf(buyEveryTick("XAUUSD")))

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
        assertThat(trades[0].price).isEqualTo(2400.5)
    }

    @Test
    fun `signal for unknown symbol produces no fill`() {
        val emitForUnknown =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSD", 1.0))
                }
            }
        wirePipeline(listOf(emitForUnknown))

        engine.onTick(Tick("XAUUSD", 2400.0, 999L))

        assertThat(trades).isEmpty()
    }

    @Test
    fun `multiple strategies all see the same tick`() {
        val seenByA = mutableListOf<Tick>()
        val seenByB = mutableListOf<Tick>()
        val a =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    seenByA.add(tick)
                }
            }
        val b =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    seenByB.add(tick)
                }
            }
        wirePipeline(listOf(a, b))

        val tick = Tick("XAUUSD", 2400.0, 999L)
        engine.onTick(tick)

        assertThat(seenByA).containsExactly(tick)
        assertThat(seenByB).containsExactly(tick)
    }

    @Test
    fun `order ids are sequential across multiple signals`() {
        val emitTwo =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 1.0))
                    emit(Signal.Sell("XAUUSD", 1.0))
                }
            }
        wirePipeline(listOf(emitTwo), captureOrders = true)

        engine.onTick(Tick("XAUUSD", 2400.0, 999L))

        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals from one tick all fill at same tracker price`() {
        val emitTwoBuys =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 1.0))
                    emit(Signal.Buy("XAUUSD", 2.0))
                }
            }
        wirePipeline(listOf(emitTwoBuys))

        engine.onTick(Tick("XAUUSD", 2400.5, 999L))

        assertThat(trades).hasSize(2)
        assertThat(trades.map { it.price }).containsExactly(2400.5, 2400.5)
    }

    @Test
    fun `cross-symbol strategy emits signal for symbol B from tick of symbol A`() {
        tracker.update("XAUUSD", 2400.0)
        val watchEurTradeGold =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    if (tick.symbol == "EURUSD") emit(Signal.Buy("XAUUSD", 1.0))
                }
            }
        wirePipeline(listOf(watchEurTradeGold))

        engine.onTick(Tick("EURUSD", 1.0921, 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].price).isEqualTo(2400.0)
    }

    @Test
    fun `tick stream spanning a window boundary produces a CandleEvent`() {
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val captured = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { captured.add(it.candle) }
        wirePipeline(emptyList())

        engine.onTick(Tick("XAUUSD", 2400.0, 0L))
        engine.onTick(Tick("XAUUSD", 2401.0, 30_000L))
        engine.onTick(Tick("XAUUSD", 2402.0, 75_000L))

        assertThat(captured).hasSize(1)
        assertThat(captured[0].symbol).isEqualTo("XAUUSD")
        assertThat(captured[0].startTime).isEqualTo(0L)
    }

    @Test
    fun `strategy receiving onCandle can emit a signal that fills`() {
        tracker.update("XAUUSD", 2400.0)
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val candleStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                }

                override fun onCandle(
                    candle: Candle,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy(candle.symbol, 1.0))
                }
            }
        wirePipeline(listOf(candleStrategy))

        engine.onTick(Tick("XAUUSD", 2400.0, 30_000L))
        engine.onTick(Tick("XAUUSD", 2400.5, 75_000L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
    }

    @Test
    fun `aggregator subscribes before strategies see the same tick`() {
        val sequence = mutableListOf<String>()
        val orderingStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onTick(${tick.timestamp})")
                }

                override fun onCandle(
                    candle: Candle,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onCandle(${candle.startTime})")
                }
            }
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        wirePipeline(listOf(orderingStrategy))

        engine.onTick(Tick("XAUUSD", 2400.0, 30_000L))
        engine.onTick(Tick("XAUUSD", 2401.0, 75_000L))

        assertThat(sequence).containsExactly(
            "onTick(30000)",
            "onCandle(0)",
            "onTick(75000)",
        )
    }
}
