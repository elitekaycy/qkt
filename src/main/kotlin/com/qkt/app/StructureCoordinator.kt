package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import java.math.BigDecimal

/**
 * Keeps an option structure whole after its legs leave together (spec §6.5, E21). Option legs fill or
 * are cancelled on later snapshots, possibly apart, so when one leg ends without filling after the
 * group was accepted, the structure is unwound:
 * - its still-working legs are cancelled through [cancel];
 * - its filled legs are closed by one forced group of market orders (`unwind:<structure>`), which the
 *   margin rule judges with every close filled (so it never trips on the moment between closes) and
 *   the gate never drops, since it only removes risk.
 *
 * A leg filling after the unwind began is closed as it fills, and an unwind leg that is itself
 * cancelled is sent again for what it still has to close. A group refused by risk sent nothing and is
 * forgotten.
 */
internal class StructureCoordinator(
    private val bus: EventBus,
    private val clock: Clock,
    private val cancel: (String) -> Unit,
) {
    private class Leg(
        val request: OrderRequest,
    ) {
        var filled: BigDecimal = BigDecimal.ZERO
        var ended = false
    }

    private class Structure(
        val id: String,
        val legs: List<Leg>,
    ) {
        val isUnwind = id.startsWith(UNWIND)
        var unwinding = false
    }

    /** Follow [strategyId]'s structures, unwinding through [emit]. */
    fun bind(
        strategyId: String,
        emit: (Signal) -> Unit,
    ) {
        val ids = SequentialIdGenerator(prefix = "unwind-$strategyId-")
        val byLeg = HashMap<String, Pair<Structure, Leg>>()

        fun close(
            structureId: String,
            closes: List<Triple<String, Side, BigDecimal>>,
        ) {
            val live = closes.filter { it.third.signum() > 0 }.sortedBy { if (it.second == Side.BUY) 0 else 1 }
            if (live.isEmpty()) return
            val requests =
                live.map { (symbol, side, qty) ->
                    OrderRequest.Market(ids.next(), symbol, side, qty, TimeInForce.GTC, clock.now(), strategyId)
                }
            emit(Signal.SubmitGroup("$UNWIND$structureId-${ids.next()}", requests, force = true))
        }

        fun closeOf(leg: Leg) =
            Triple(
                leg.request.symbol,
                if (leg.request.side ==
                    Side.SELL
                ) {
                    Side.BUY
                } else {
                    Side.SELL
                },
                leg.filled,
            )

        fun forgetIfDone(structure: Structure) {
            if (structure.legs.all { it.ended }) structure.legs.forEach { byLeg.remove(it.request.id) }
        }

        bus.subscribe<SignalEvent> { e ->
            val group = e.signal as? Signal.SubmitGroup ?: return@subscribe
            if (e.strategyId != strategyId) return@subscribe
            val structure = Structure(group.structureId, group.requests.map(::Leg))
            structure.legs.forEach { byLeg[it.request.id] = structure to it }
        }
        bus.subscribe<RiskRejectedEvent> { e ->
            byLeg[e.request.id]?.let { (structure, _) -> structure.legs.forEach { byLeg.remove(it.request.id) } }
        }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            val (structure, leg) = byLeg[e.clientOrderId] ?: return@subscribe
            leg.filled = leg.filled.add(e.quantity)
            leg.ended = true
            if (structure.unwinding) close(structure.id, listOf(closeOf(leg)))
            forgetIfDone(structure)
        }
        val failed = { id: String ->
            byLeg[id]?.let { (structure, leg) ->
                leg.ended = true
                if (structure.isUnwind) {
                    val left = leg.request.quantity.subtract(leg.filled)
                    close(structure.id.removePrefix(UNWIND), listOf(Triple(leg.request.symbol, leg.request.side, left)))
                } else if (!structure.unwinding) {
                    structure.unwinding = true
                    structure.legs.filter { !it.ended }.forEach { cancel(it.request.id) }
                    close(structure.id, structure.legs.map(::closeOf))
                }
                forgetIfDone(structure)
            }
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e -> failed(e.clientOrderId) }
        bus.subscribe<BrokerEvent.OrderRejected> { e -> failed(e.clientOrderId) }
    }

    private companion object {
        const val UNWIND = "unwind:"
    }
}
