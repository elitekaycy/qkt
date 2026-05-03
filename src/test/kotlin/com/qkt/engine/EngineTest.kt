package com.qkt.engine

import com.qkt.broker.Broker
import com.qkt.broker.MockBroker
import com.qkt.common.FixedClock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.execution.Order
import com.qkt.execution.OrderType
import com.qkt.execution.Trade
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EngineTest {
    private val clock = FixedClock(time = 1000L)
    private val ids = SequentialIdGenerator(prefix = "ORD")
    private val tracker = MarketPriceTracker()

    private fun engineWith(
        strategy: Strategy,
        broker: Broker = MockBroker(clock, tracker),
        trades: MutableList<Trade> = mutableListOf(),
    ): Pair<Engine, MutableList<Trade>> =
        Engine(strategy, broker, clock, ids, tracker, onTrade = { trades.add(it) }) to trades

    @Test
    fun `updates price tracker before strategy sees the tick`() {
        val seenPrices = mutableListOf<Double?>()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    seenPrices.add(tracker.lastPrice("XAUUSD"))
                }
            }
        val (engine, _) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(seenPrices).containsExactly(2400.5)
    }

    @Test
    fun `forwards tick to strategy`() {
        val seen = mutableListOf<Tick>()
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    seen.add(tick)
                }
            }
        val (engine, _) = engineWith(strategy)
        val tick = Tick("XAUUSD", 2400.0, 1000L)
        engine.onTick(tick)
        assertThat(seen).containsExactly(tick)
    }

    @Test
    fun `converts Buy signal to MARKET BUY order`() {
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 2.0))
                }
            }
        val orders = mutableListOf<Order>()
        val capturingBroker =
            object : Broker {
                override fun execute(order: Order): Trade? {
                    orders.add(order)
                    return null
                }
            }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders).hasSize(1)
        val order = orders[0]
        assertThat(order.symbol).isEqualTo("XAUUSD")
        assertThat(order.side).isEqualTo(Side.BUY)
        assertThat(order.quantity).isEqualTo(2.0)
        assertThat(order.type).isEqualTo(OrderType.MARKET)
        assertThat(order.price).isNull()
        assertThat(order.id).isEqualTo("ORD-0")
        assertThat(order.timestamp).isEqualTo(1000L)
    }

    @Test
    fun `converts Sell signal to MARKET SELL order`() {
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Sell("XAUUSD", 3.0))
                }
            }
        val orders = mutableListOf<Order>()
        val capturingBroker =
            object : Broker {
                override fun execute(order: Order): Trade? {
                    orders.add(order)
                    return null
                }
            }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders).hasSize(1)
        assertThat(orders[0].side).isEqualTo(Side.SELL)
        assertThat(orders[0].quantity).isEqualTo(3.0)
    }

    @Test
    fun `routes order to broker and forwards trade to onTrade`() {
        tracker.update("XAUUSD", 2400.5)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 1.0))
                }
            }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(trades).hasSize(1)
        assertThat(trades[0].price).isEqualTo(2400.5)
    }

    @Test
    fun `skips onTrade when broker returns null`() {
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSD", 1.0))
                }
            }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(trades).isEmpty()
    }

    @Test
    fun `assigns sequential ids to multiple signals from one tick`() {
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 1.0))
                    emit(Signal.Sell("XAUUSD", 1.0))
                }
            }
        val orders = mutableListOf<Order>()
        val capturingBroker =
            object : Broker {
                override fun execute(order: Order): Trade? {
                    orders.add(order)
                    return null
                }
            }
        val (engine, _) = engineWith(strategy, capturingBroker)
        engine.onTick(Tick("XAUUSD", 2400.0, 1000L))
        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals all fill at same tracker price`() {
        tracker.update("XAUUSD", 2400.5)
        val strategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", 1.0))
                    emit(Signal.Buy("XAUUSD", 2.0))
                }
            }
        val (engine, trades) = engineWith(strategy)
        engine.onTick(Tick("XAUUSD", 2400.5, 1000L))
        assertThat(trades.map { it.price }).containsExactly(2400.5, 2400.5)
    }
}
