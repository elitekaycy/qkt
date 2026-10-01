package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Keeps an option structure whole after its legs leave together (spec §6.5, E21). Option legs fill
 * or are cancelled on later snapshots, possibly apart, so when one leg ends without filling after
 * the group was accepted, the structure is unwound:
 * - its still-working legs are cancelled through [cancel];
 * - its filled legs are closed at market, shorts first, so no moment holds a short without its wing.
 *
 * A leg filling after the unwind began is closed as it fills. A group refused by risk sent nothing,
 * so it is simply forgotten.
 */
internal class StructureCoordinator(
    private val bus: EventBus,
    private val cancel: (String) -> Unit,
) {
    private class Leg(
        val id: String,
        val symbol: String,
        val side: Side,
    ) {
        var filled: BigDecimal = BigDecimal.ZERO
        var ended = false
    }

    private class Structure(
        val legs: List<Leg>,
    ) {
        var unwinding = false
    }

    /** Follow [strategyId]'s structures, closing legs through [emit]. */
    fun bind(
        strategyId: String,
        emit: (Signal) -> Unit,
    ) {
        val byLeg = HashMap<String, Pair<Structure, Leg>>()

        fun close(leg: Leg) {
            if (leg.filled.signum() == 0) return
            emit(if (leg.side == Side.SELL) Signal.Buy(leg.symbol, leg.filled) else Signal.Sell(leg.symbol, leg.filled))
            leg.filled = BigDecimal.ZERO
        }

        fun unwind(structure: Structure) {
            if (structure.unwinding) return
            structure.unwinding = true
            structure.legs.filter { !it.ended }.forEach { cancel(it.id) }
            structure.legs.sortedBy { if (it.side == Side.SELL) 0 else 1 }.forEach(::close)
        }

        fun forget(structure: Structure) = structure.legs.forEach { byLeg.remove(it.id) }

        bus.subscribe<SignalEvent> { e ->
            val group = e.signal as? Signal.SubmitGroup ?: return@subscribe
            if (e.strategyId != strategyId) return@subscribe
            val structure = Structure(group.requests.map { Leg(it.id, it.symbol, it.side) })
            structure.legs.forEach { byLeg[it.id] = structure to it }
        }
        bus.subscribe<RiskRejectedEvent> { e -> byLeg[e.request.id]?.let { (structure, _) -> forget(structure) } }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            val (structure, leg) = byLeg[e.clientOrderId] ?: return@subscribe
            leg.filled = leg.filled.add(e.quantity)
            leg.ended = true
            if (structure.unwinding) close(leg)
            if (structure.legs.all { it.ended }) forget(structure)
        }
        val failed = { id: String ->
            byLeg[id]?.let { (structure, leg) ->
                leg.ended = true
                unwind(structure)
                if (structure.legs.all { it.ended }) forget(structure)
            }
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> failed(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> failed(e.clientOrderId) }
    }
}
