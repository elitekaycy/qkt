package com.qkt.app

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.events.RiskRejectedEvent
import com.qkt.events.SignalEvent
import com.qkt.events.TickEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureState

/**
 * Drives a strategy's [StructureBook] from bus events and keeps each structure whole (spec §6.5,
 * E21). Option legs fill or are cancelled on later snapshots, possibly apart, so when an opening leg
 * ends without filling, the structure is unwound:
 * - its still-working opening legs are cancelled through [cancel];
 * - its filled legs are closed by one forced group of market orders, which the margin rule judges
 *   with every close filled and the gate never drops, since it only removes risk.
 *
 * An opening leg filling after the unwind began is closed as it fills. A closing leg the venue
 * cancels (no quote in time) is sent again for what it still holds; one the venue rejects is final.
 * Legs whose contract has expired are never closed: they settle from the clock at their delivery price
 * ([StructureBook.settleExpired]) or from the venue's settlement print, whichever comes first. A fill of an
 * order no structure sent (a flatten) closes the structure legs it trades against ([StructureBook.external]).
 */
internal class StructureCoordinator(
    private val bus: EventBus,
    private val clock: Clock,
    private val cancel: (String) -> Unit,
) {
    /** Follow [strategyId]'s structures in [book], unwinding through [emit]. */
    fun bind(
        strategyId: String,
        book: StructureBook,
        emit: (Signal) -> Unit,
    ) {
        val ids = SequentialIdGenerator(prefix = "unwind-$strategyId-")

        fun close(
            structure: LiveStructure,
            legs: List<StructureLeg>,
        ) {
            val now = clock.now()
            val requests =
                legs
                    .filter { it.expiryMs > now && it.unclosed.signum() > 0 }
                    .sortedBy { if (it.side == Side.SELL) 0 else 1 }
                    .map { leg ->
                        val side = if (leg.side == Side.SELL) Side.BUY else Side.SELL
                        OrderRequest.Market(
                            ids.next(),
                            leg.symbol,
                            side,
                            leg.unclosed,
                            TimeInForce.GTC,
                            now,
                            strategyId,
                        )
                    }
            if (requests.isEmpty()) return
            emit(Signal.SubmitGroup(ids.next(), structure.alias, requests, closes = structure.id))
        }

        fun failed(owner: StructureBook.Owner) {
            val structure = owner.structure
            if (structure.state != StructureState.PENDING) return
            book.unwinding(structure)
            structure.legs.filter { !it.openEnded }.forEach { cancel(it.openOrderId) }
            close(structure, structure.legs)
        }

        bus.subscribe<SignalEvent> { e ->
            val group = e.signal as? Signal.SubmitGroup ?: return@subscribe
            if (e.strategyId == strategyId) book.accept(group)
        }
        bus.subscribe<TickEvent> { book.settleExpired(clock.now()) }
        bus.subscribe<RiskRejectedEvent> { e -> if (e.request.strategyId == strategyId) book.refused(e.request.id) }
        bus.subscribe<BrokerEvent.OrderFilled> { e ->
            if (e.strategyId != strategyId) return@subscribe
            if (e.exitReason == ExitReason.EXPIRY) {
                book.settled(e.symbol, e.price)
                return@subscribe
            }
            val owner = book.filled(e.clientOrderId, e.quantity, e.price)
            if (owner == null) {
                book.external(e.symbol, e.side, e.quantity, e.price)
                return@subscribe
            }
            if (owner.opening &&
                owner.structure.state == StructureState.UNWINDING
            ) {
                close(owner.structure, listOf(owner.leg))
            }
        }
        bus.subscribe<BrokerEvent.OrderCancelled> { e ->
            if (e.strategyId != strategyId) return@subscribe
            val owner = book.ended(e.clientOrderId) ?: return@subscribe
            if (owner.opening) failed(owner) else close(owner.structure, listOf(owner.leg))
        }
        bus.subscribe<BrokerEvent.OrderRejected> { e ->
            if (e.strategyId != strategyId) return@subscribe
            val owner = book.ended(e.clientOrderId) ?: return@subscribe
            if (owner.opening) failed(owner)
        }
    }
}
