package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
import com.qkt.common.TradingCalendar
import com.qkt.engine.Engine
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.events.WarmupTickEvent
import com.qkt.execution.Trade
import com.qkt.execution.toOrderRequest
import com.qkt.marketdata.Candle
import com.qkt.marketdata.MarketPriceTracker
import com.qkt.marketdata.Tick
import com.qkt.marketdata.source.MarketSource
import com.qkt.pnl.PnLCalculator
import com.qkt.pnl.StrategyPnL
import com.qkt.pnl.StrategyPnLViewImpl
import com.qkt.positions.PositionTracker
import com.qkt.positions.StrategyPositionTracker
import com.qkt.positions.StrategyPositionViewImpl
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.strategy.Mode
import com.qkt.strategy.Strategy
import com.qkt.strategy.StrategyContext
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val strategyPositions: StrategyPositionTracker,
    val strategyPnL: StrategyPnL,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Pair<String, Strategy>>,
    val riskEngine: RiskEngine,
    val riskState: com.qkt.risk.RiskState,
    val mode: Mode,
    val calendar: TradingCalendar,
    val source: MarketSource,
    val candleWindow: TimeWindow? = null,
    val candleHub: com.qkt.dsl.compile.CandleHub =
        com.qkt.dsl.compile
            .CandleHub(),
    val onFilled: (Trade, BigDecimal, String) -> Unit = { _, _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    val orderManager: OrderManager = OrderManager(broker, bus, priceTracker, clock)

    init {
        require(strategies.map { it.first }.toSet().size == strategies.size) {
            "Strategy IDs must be unique: ${strategies.map { it.first }}"
        }
        require(strategies.all { it.first.isNotBlank() }) {
            "Strategy ID must be non-blank"
        }

        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        bus.subscribe<WarmupTickEvent> { e -> priceTracker.update(e.tick.symbol, e.tick.price) }

        strategies.forEach { (strategyId, strategy) ->
            val ctx =
                StrategyContext(
                    strategyId = strategyId,
                    mode = mode,
                    clock = clock,
                    calendar = calendar,
                    source = source,
                    positions = StrategyPositionViewImpl(strategyPositions, strategyId),
                    pnl = StrategyPnLViewImpl(strategyPnL, strategyId),
                    risk = com.qkt.risk.RiskViewImpl(riskState, strategyId),
                )
            val emit: (com.qkt.strategy.Signal) -> Unit = { sig ->
                bus.publish(SignalEvent(sig))
                if (sig is com.qkt.strategy.Signal.CancelStacksForSymbol) {
                    orderManager.cancelStacksForSymbol(sig.symbol)
                } else {
                    val request = sig.toOrderRequest(ids.next(), clock.now(), strategyId = strategyId)
                    if (request != null) {
                        when (val decision = riskEngine.approve(request)) {
                            is Decision.Approve -> bus.publish(OrderEvent(request))
                            is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
                        }
                    }
                }
            }
            if (strategy is com.qkt.dsl.compile.DslCompiledStrategy) {
                for ((key, retention) in strategy.retentionByKey) candleHub.register(key, retention)
                strategy.bindToHub(candleHub, ctx, emit)
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
            } else {
                bus.subscribe<TickEvent> { e -> strategy.onTick(e.tick, ctx, emit) }
                bus.subscribe<CandleEvent> { e -> strategy.onCandle(e.candle, ctx, emit) }
            }
        }
        bus.subscribe<TickEvent> { _ ->
            riskState.onTick()
            riskEngine.evaluateHaltRules()
        }
        bus.subscribe<OrderEvent> { e ->
            orderManager.submit(e.request)
        }
        bus.subscribe<BrokerEvent.PositionReconciled> { e ->
            positions.reset(e.symbol, e.newQty, e.newAvgPx)
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            val realized = positions.applyFill(e)
            pnl.recordRealized(realized)

            val stratRealized = strategyPositions.applyFill(e)
            strategyPnL.recordRealized(e.strategyId, stratRealized)
            riskState.onFill(e.strategyId, stratRealized)
            riskEngine.evaluateHaltRules()

            val trade =
                Trade(
                    orderId = e.clientOrderId,
                    symbol = e.symbol,
                    price = e.price,
                    quantity = e.quantity,
                    side = e.side,
                    timestamp = e.timestamp,
                )
            bus.publish(TradeEvent(trade))
            onFilled(trade, realized, e.strategyId)
        }
        bus.subscribe<BrokerEvent.OrderPartiallyFilled> { e ->
            val asFill =
                BrokerEvent.OrderFilled(
                    clientOrderId = e.clientOrderId,
                    brokerOrderId = e.brokerOrderId,
                    symbol = e.symbol,
                    side = e.side,
                    price = e.price,
                    quantity = e.quantity,
                    strategyId = e.strategyId,
                    timestamp = e.timestamp,
                )
            val realized = positions.applyFill(asFill)
            pnl.recordRealized(realized)

            val stratRealized = strategyPositions.applyFill(asFill)
            strategyPnL.recordRealized(e.strategyId, stratRealized)
            riskState.onFill(e.strategyId, stratRealized)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
        candleHub.feed(tick)
    }

    fun ingestForWarmup(tick: Tick) {
        bus.publish(WarmupTickEvent(tick))
    }
}
