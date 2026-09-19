package com.qkt.app

import com.qkt.broker.PaperBroker
import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.Money
import com.qkt.common.MonotonicSequenceGenerator
import com.qkt.common.SequentialIdGenerator
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
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.StrategyPositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.risk.RiskRule
import com.qkt.strategy.Signal
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import com.qkt.strategy.testStrategyContext

abstract class EndToEndFixtures {
    protected val clock = FixedClock(time = 1000L)
    protected val ids = SequentialIdGenerator()
    protected val sequencer = MonotonicSequenceGenerator()
    protected val tracker = MarketPriceTracker()
    protected val ledger = StrategyPositionTracker()
    protected val positions = ledger.account
    protected val bus = EventBus(clock, sequencer)
    protected val broker = PaperBroker(bus, clock, tracker)
    protected val engine = Engine(bus, tracker)
    protected val trades = mutableListOf<Trade>()
    protected val orders = mutableListOf<OrderRequest>()
    protected val pnl = PnLCalculator(positions, tracker)

    protected fun wirePipeline(
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
            val realized = ledger.apply("e2e", trade)
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
            val request = e.signal.toOrderRequest(ids.next(), clock.now()) ?: return@subscribe
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

    protected fun buyEveryTick(symbol: String) =
        object : Strategy {
            override fun onTick(
                tick: Tick,
                ctx: StrategyContext,
                emit: (Signal) -> Unit,
            ) {
                if (tick.symbol == symbol) emit(Signal.Buy(symbol, Money.of("1")))
            }
        }
}
