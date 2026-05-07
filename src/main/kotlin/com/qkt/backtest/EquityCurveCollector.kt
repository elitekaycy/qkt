package com.qkt.backtest

import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.CandleEvent
import com.qkt.events.TickEvent
import com.qkt.pnl.PnLProvider
import com.qkt.pnl.StrategyPnL
import java.math.BigDecimal

class EquityCurveCollector(
    private val cadence: SampleCadence,
    bus: EventBus,
    private val pnl: PnLProvider,
    private val strategyPnL: StrategyPnL,
    strategyIds: List<String>,
) {
    private val globalCurve: MutableList<EquitySample> = mutableListOf()
    private val perStrategy: Map<String, MutableList<EquitySample>> =
        strategyIds.associateWith { mutableListOf() }

    init {
        when (cadence) {
            SampleCadence.CANDLE_CLOSE ->
                bus.subscribe<CandleEvent> { e -> sample(e.candle.endTime) }
            SampleCadence.TICK ->
                bus.subscribe<TickEvent> { e -> sample(e.tick.timestamp) }
            SampleCadence.FILL ->
                bus.subscribe<BrokerEvent.OrderFilled> { e -> sample(e.timestamp) }
        }
    }

    fun global(): List<EquitySample> = globalCurve.toList()

    fun forStrategy(strategyId: String): List<EquitySample> =
        perStrategy[strategyId]?.toList() ?: emptyList()

    private fun sample(timestamp: Long) {
        val globalEquity: BigDecimal = pnl.realizedTotal().add(pnl.unrealizedTotal())
        globalCurve.add(EquitySample(timestamp, globalEquity))
        for ((strategyId, list) in perStrategy) {
            list.add(EquitySample(timestamp, strategyPnL.totalFor(strategyId)))
        }
    }
}
