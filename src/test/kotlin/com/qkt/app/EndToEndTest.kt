package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.Trade
import com.qkt.execution.toOrderRequest
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.risk.rules.MaxPositionSize
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class EndToEndTest {
    private val clock = FixedClock(time = 1000L)
    private val ids = SequentialIdGenerator()
    private val sequencer = MonotonicSequenceGenerator()
    private val tracker = MarketPriceTracker()
    private val positions = PositionTracker()
    private val bus = EventBus(clock, sequencer)
    private val broker = PaperBroker(bus, clock, tracker)
    private val engine = Engine(bus, tracker)
    private val trades = mutableListOf<Trade>()
    private val orders = mutableListOf<OrderRequest>()
    private val pnl = PnLCalculator(positions, tracker)

    private fun wirePipeline(
        strategies: List<Strategy>,
        captureOrders: Boolean = false,
        rules: List<RiskRule> = emptyList(),
    ) {
        val riskEngine = RiskEngine(rules, positions)
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            val trade =
                Trade(
                    orderId = e.clientOrderId,
                    symbol = e.symbol,
                    price = e.price,
                    quantity = e.quantity,
                    side = e.side,
                    timestamp = e.timestamp,
                )
            val realized = positions.apply(trade)
            pnl.recordRealized(realized)
            bus.publish(TradeEvent(trade))
        }
        strategies.forEach { s ->
            bus.subscribe<TickEvent> { e ->
                s.onTick(e.tick, testStrategyContext()) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                s.onCandle(e.candle, testStrategyContext()) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val request = e.signal.toOrderRequest(ids.next(), clock.now())
            if (captureOrders) orders.add(request)
            when (val decision = riskEngine.approve(request)) {
                is Decision.Approve -> bus.publish(OrderEvent(request))
                is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
            }
        }
        bus.subscribe<OrderEvent> { e ->
            broker.submit(e.request)
        }
        bus.subscribe<TradeEvent> { e -> trades.add(e.trade) }
    }

    private fun buyEveryTick(symbol: String) =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (tick.symbol == symbol) emit(Signal.Buy(symbol, Money.of("1")))
            }
        }

    @Test
    fun `single strategy buy on every tick produces a fill`() {
        wirePipeline(listOf(buyEveryTick("XAUUSD")))

        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].side).isEqualTo(Side.BUY)
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `signal for unknown symbol produces no fill`() {
        val emitForUnknown =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("BTCUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(emitForUnknown))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

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
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seenByA.add(tick)
                }
            }
        val b =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    seenByB.add(tick)
                }
            }
        wirePipeline(listOf(a, b))

        val tick = Tick("XAUUSD", Money.of("2400.0"), 999L)
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
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", Money.of("1")))
                    emit(Signal.Sell("XAUUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(emitTwo), captureOrders = true)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(orders.map { it.id }).containsExactly("ORD-0", "ORD-1")
    }

    @Test
    fun `multiple signals from one tick all fill at same tracker price`() {
        val emitTwoBuys =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy("XAUUSD", Money.of("1")))
                    emit(Signal.Buy("XAUUSD", Money.of("2")))
                }
            }
        wirePipeline(listOf(emitTwoBuys))

        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 999L))

        assertThat(trades).hasSize(2)
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.5"))
        assertThat(trades[1].price).isEqualByComparingTo(Money.of("2400.5"))
    }

    @Test
    fun `cross-symbol strategy emits signal for symbol B from tick of symbol A`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        val watchEurTradeGold =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (tick.symbol == "EURUSD") emit(Signal.Buy("XAUUSD", Money.of("1")))
                }
            }
        wirePipeline(listOf(watchEurTradeGold))

        engine.onTick(Tick("EURUSD", Money.of("1.0921"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(trades[0].symbol).isEqualTo("XAUUSD")
        assertThat(trades[0].price).isEqualByComparingTo(Money.of("2400.0"))
    }

    @Test
    fun `tick stream spanning a window boundary produces a CandleEvent`() {
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val captured = mutableListOf<Candle>()
        bus.subscribe<CandleEvent> { captured.add(it.candle) }
        wirePipeline(emptyList())

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 0L))
        engine.onTick(Tick("XAUUSD", Money.of("2401.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2402.0"), 75_000L))

        assertThat(captured).hasSize(1)
        assertThat(captured[0].symbol).isEqualTo("XAUUSD")
        assertThat(captured[0].startTime).isEqualTo(0L)
    }

    @Test
    fun `strategy receiving onCandle can emit a signal that fills`() {
        tracker.update("XAUUSD", Money.of("2400.0"))
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        val candleStrategy =
            object : Strategy {
                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                }

                override fun onCandle(
                    candle: Candle,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    emit(Signal.Buy(candle.symbol, Money.of("1")))
                }
            }
        wirePipeline(listOf(candleStrategy))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2400.5"), 75_000L))

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
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onTick(${tick.timestamp})")
                }

                override fun onCandle(
                    candle: Candle,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    sequence.add("onCandle(${candle.startTime})")
                }
            }
        CandleAggregator(bus, TimeWindow.ONE_MINUTE)
        wirePipeline(listOf(orderingStrategy))

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 30_000L))
        engine.onTick(Tick("XAUUSD", Money.of("2401.0"), 75_000L))

        assertThat(sequence).containsExactly(
            "onTick(30000)",
            "onCandle(0)",
            "onTick(75000)",
        )
    }

    @Test
    fun `risk approved order produces a fill and updates positions`() {
        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("5")))
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy), rules = rules)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(positions.positionFor("XAUUSD")?.quantity).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `risk rejected order publishes RiskRejectedEvent and skips broker`() {
        val rejections = mutableListOf<RiskRejectedEvent>()
        bus.subscribe<RiskRejectedEvent> { rejections.add(it) }

        val rules = listOf<RiskRule>(MaxPositionSize("XAUUSD", maxQty = Money.of("0.5")))
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy), rules = rules)

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).isEmpty()
        assertThat(rejections).hasSize(1)
        assertThat(rejections[0].request.symbol).isEqualTo("XAUUSD")
        assertThat(rejections[0].reason).contains("MaxPositionSize")
    }

    @Test
    fun `position tracker is updated before subsequent FILLED log read`() {
        val seenPositions = mutableListOf<BigDecimal?>()
        val strategy = buyEveryTick("XAUUSD")
        wirePipeline(listOf(strategy))
        bus.subscribe<TradeEvent> { e ->
            seenPositions.add(positions.positionFor(e.trade.symbol)?.quantity)
        }

        engine.onTick(Tick("XAUUSD", Money.of("2400.0"), 999L))

        assertThat(trades).hasSize(1)
        assertThat(seenPositions).hasSize(1)
        assertThat(seenPositions[0]).isEqualByComparingTo(Money.of("1"))
    }

    @Test
    fun `realized PnL accumulates after a closing trade`() {
        val sellAfterBuy =
            object : Strategy {
                private var bought = false

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (!bought) {
                        emit(Signal.Buy("XAUUSD", Money.of("1")))
                        bought = true
                    } else {
                        emit(Signal.Sell("XAUUSD", Money.of("1")))
                    }
                }
            }
        wirePipeline(listOf(sellAfterBuy))

        engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))
        engine.onTick(Tick("XAUUSD", Money.of("120"), 1000L))

        assertThat(trades).hasSize(2)
        assertThat(pnl.realizedTotal()).isEqualByComparingTo(Money.of("20"))
    }

    @Test
    fun `unrealized PnL is visible after an open position`() {
        val buyOnce =
            object : Strategy {
                private var done = false

                override fun onTick(
                    tick: Tick,
                    ctx: StrategyContext,
                    emit: (Signal) -> Unit,
                ) {
                    if (!done) {
                        emit(Signal.Buy("XAUUSD", Money.of("2")))
                        done = true
                    }
                }
            }
        wirePipeline(listOf(buyOnce))

        engine.onTick(Tick("XAUUSD", Money.of("100"), 999L))
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.ZERO)

        engine.onTick(Tick("XAUUSD", Money.of("110"), 1000L))
        assertThat(pnl.unrealizedFor("XAUUSD")).isEqualByComparingTo(Money.of("20"))
    }
}
