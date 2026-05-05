package com.qkt.app

import com.qkt.broker.Broker
import com.qkt.bus.EventBus
import com.qkt.candles.CandleAggregator
import com.qkt.candles.TimeWindow
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequenceGenerator
import com.qkt.engine.Engine
import com.qkt.events.CandleEvent
import com.qkt.events.OrderEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.events.TradeEvent
import com.qkt.execution.Trade
import com.qkt.execution.toOrder
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
    init {
        if (candleWindow != null) CandleAggregator(bus, candleWindow)

        strategies.forEach { strategy ->
            bus.subscribe<TickEvent> { e ->
                strategy.onTickWithContext(e.tick, sessionContext) { sig -> bus.publish(SignalEvent(sig)) }
            }
            bus.subscribe<CandleEvent> { e ->
                strategy.onCandle(e.candle) { sig -> bus.publish(SignalEvent(sig)) }
            }
        }
        bus.subscribe<SignalEvent> { e ->
            val order = e.signal.toOrder(ids.next(), clock.now())
            when (val decision = riskEngine.approve(order)) {
                is Decision.Approve -> bus.publish(OrderEvent(order))
                is Decision.Reject -> bus.publish(RiskRejectedEvent(order, decision.reason))
            }
        }
        bus.subscribe<OrderEvent> { e ->
            broker.execute(e.order)?.let { bus.publish(TradeEvent(it)) }
        }
        bus.subscribe<TradeEvent> { e ->
            val realized = positions.apply(e.trade)
            pnl.recordRealized(realized)
            onFilled(e.trade, realized)
        }
        bus.subscribe<RiskRejectedEvent> { e -> onRejected(e) }
        bus.subscribe<CandleEvent> { e -> onCandle(e.candle) }
    }

    fun ingest(tick: Tick) {
        engine.onTick(tick)
    }
}
