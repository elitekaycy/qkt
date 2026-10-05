package com.qkt.broker.liquidation

import com.qkt.accounting.margin.MaintenanceMargin
import com.qkt.bus.EventBus
import com.qkt.events.BrokerEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExitReason
import com.qkt.marketdata.source.SymbolPattern
import java.math.BigDecimal

/**
 * A backtest's liquidation engine. On every tick, once the account holds futures positions whose
 * roots declare margin terms, it compares account equity, marked at the tick's prices, with their total
 * [MaintenanceMargin]. Below it, every such position is closed by the venue that holds it
 * ([LiquidatingVenue]), symbol by symbol in sorted order, and each close is recorded in [log] with the
 * equity and maintenance that triggered it. While equity stays below maintenance (a contract no venue
 * could price, or a continuous stream mid-roll), [belowMaintenance] holds and the
 * [com.qkt.risk.rules.MaintenanceMarginGate] refuses orders that add risk; the next tick tries again.
 *
 * Cost per tick: one pass over the account's open symbols with a cached margined-or-not lookup; the
 * margin and equity are computed only while a margined position is held.
 */
class MarginLiquidator(
    private val maintenance: MaintenanceMargin,
) {
    private val venues = mutableListOf<Pair<SymbolPattern, LiquidatingVenue>>()
    private var equity: () -> BigDecimal = { BigDecimal.ZERO }
    private var trigger: Pair<BigDecimal, BigDecimal>? = null

    /** Every liquidation so far. */
    val log = LiquidationLog()

    /** Whether equity was below maintenance after the last tick's liquidation. */
    var belowMaintenance: Boolean = false
        private set

    /** Liquidate the symbols [routes] matches on [venue]; the first registered match wins. */
    fun register(
        routes: SymbolPattern,
        venue: LiquidatingVenue,
    ) {
        venues += routes to venue
    }

    /** Read account equity from [equity], check every tick of [bus] and record the liquidations it publishes. */
    fun attach(
        bus: EventBus,
        equity: () -> BigDecimal,
    ) {
        this.equity = equity
        bus.subscribe<TickEvent> { e -> onTick(e.tick.timestamp) }
        bus.subscribe<BrokerEvent.OrderFilled> { e -> if (e.exitReason == ExitReason.LIQUIDATION) record(e) }
    }

    /** Liquidate every margined position when equity at [nowMs] is below their maintenance margin. */
    fun onTick(nowMs: Long) {
        if (!maintenance.holdsAny()) {
            belowMaintenance = false
            return
        }
        val required = maintenance.total(nowMs)
        val available = equity()
        if (available >= required) {
            belowMaintenance = false
            return
        }
        trigger = available to required
        for (symbol in maintenance.heldSymbols()) {
            venues.firstOrNull { it.first.matches(symbol) }?.second?.liquidate(symbol)
        }
        trigger = null
        belowMaintenance = maintenance.holdsAny() && equity() < maintenance.total(nowMs)
    }

    private fun record(fill: BrokerEvent.OrderFilled) {
        val (equityAt, maintenanceAt) = trigger ?: return
        val fee = fill.typedVenueCosts.fold(BigDecimal.ZERO) { sum, cost -> sum.add(cost.amount.amount) }
        log.record(
            Liquidation(
                fill.timestamp,
                fill.strategyId,
                fill.symbol,
                fill.side,
                fill.quantity,
                fill.price,
                fee,
                equityAt,
                maintenanceAt,
            ),
        )
    }
}
