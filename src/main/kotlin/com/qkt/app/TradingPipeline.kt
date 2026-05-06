package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
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
import com.qkt.pnl.PnLCalculator
import com.qkt.positions.PositionTracker
import com.qkt.risk.Decision
import com.qkt.risk.RiskEngine
import com.qkt.strategy.SessionContext
import com.qkt.strategy.Strategy
import java.math.BigDecimal
import org.slf4j.LoggerFactory

class TradingPipeline(
    val clock: Clock,
    val ids: IdGenerator,
    val sequencer: SequenceGenerator,
    val priceTracker: MarketPriceTracker,
    val positions: PositionTracker,
    val pnl: PnLCalculator,
    val bus: EventBus,
    val broker: Broker,
    val engine: Engine,
    val strategies: List<Strategy>,
    val riskEngine: RiskEngine,
    val sessionContext: SessionContext,
    val candleWindow: TimeWindow? = null,
    val onFilled: (Trade, BigDecimal) -> Unit = { _, _ -> },
    val onRejected: (RiskRejectedEvent) -> Unit = {},
    val onCandle: (Candle) -> Unit = {},
) {
    private val log = LoggerFactory.getLogger(TradingPipeline::class.java)

    val orderManager: OrderManager = OrderManager(broker, bus, priceTracker, clock)

    init {
        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        bus.subscribe<WarmupTickEvent> { e -> priceTracker.update(e.tick.symbol, e.tick.price) }

        strategies.forEach { strategy ->
            bus.subscribe<TickEvent> { e ->
                strategy.onTick(e.tick, sessionContext) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                strategy.onCandle(e.candle, sessionContext) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val request = e.signal.toOrderRequest(ids.next(), clock.now())
            when (val decision = riskEngine.approve(request)) {
                is Decision.Approve -> bus.publish(OrderEvent(request))
                is Decision.Reject -> bus.publish(RiskRejectedEvent(request, decision.reason))
            }
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
            onFilled(trade, realized)
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
                    timestamp = e.timestamp,
                )
            val realized = positions.applyFill(asFill)
            pnl.recordRealized(realized)
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            log.warn("Order rejected: ${e.clientOrderId} reason=${e.reason}")
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
    }

    fun ingestForWarmup(tick: Tick) {
        bus.publish(WarmupTickEvent(tick))
    }
}
