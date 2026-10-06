package com.qkt.app

import com.qkt.common.Side
import com.qkt.events.StructureEvent
import com.qkt.events.StructureOutcome
import com.qkt.instrument.InstrumentRegistry
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import com.qkt.strategy.StructureView
import java.math.BigDecimal

/**
 * One strategy's option structures: the single writer of their state, driven by the
 * [StructureCoordinator] from bus events and read by the strategy as its [StructureView]. Each live
 * structure is keyed by its alias. A leg's held quantity grows with its opening fills and shrinks with
 * its closing fills and its expiry settlement, each realizing premium P&L (before fees) at its price.
 * A structure leaves the book once nothing is held and no order of it is working.
 */
internal class StructureBook(
    private val strategyId: String,
    internal val instruments: InstrumentRegistry,
    private val prices: MarketPriceProvider,
    private val publish: (StructureEvent) -> Unit = {},
) : StructureView {
    /** What an order id belongs to: [leg] of [structure], as its opening order or not. */
    data class Owner(
        val structure: LiveStructure,
        val leg: StructureLeg,
        val opening: Boolean,
    )

    private val byAlias = HashMap<String, LiveStructure>()
    private val byId = LinkedHashMap<String, LiveStructure>()
    private val owners = HashMap<String, Owner>()

    override fun live(alias: String): StructurePosition? = byAlias[alias]?.position()

    override fun all(): List<StructurePosition> = byId.values.map { it.position() }

    /** The live structures, oldest first. */
    internal val structures: Collection<LiveStructure> get() = byId.values

    override fun mark(symbol: String): BigDecimal? = prices.lastPrice(symbol)

    /** Takes back [restored] structures after a restart, with the orders their legs still have working. */
    fun restore(restored: List<LiveStructure>) {
        for (structure in restored) {
            byAlias[structure.alias] = structure
            byId[structure.id] = structure
            for (leg in structure.legs) {
                if (!leg.openEnded) owners[leg.openOrderId] = Owner(structure, leg, opening = true)
                leg.closingOrders.keys.forEach { owners[it] = Owner(structure, leg, opening = false) }
            }
        }
    }

    /** Records an accepted [group]: a new PENDING structure, or closing orders for the structure it closes. */
    fun accept(group: Signal.SubmitGroup) {
        val closes = group.closes
        if (closes == null) {
            require(group.alias !in byAlias) { "structure ${group.alias} is already live" }
            val legs = group.requests.map { structureLeg(it, instruments) }
            val structure = LiveStructure(group.structureId, group.alias, group.requests.first().quantity, legs)
            byAlias[group.alias] = structure
            byId[structure.id] = structure
            legs.forEach { owners[it.openOrderId] = Owner(structure, it, opening = true) }
            return
        }
        val structure = byId[closes] ?: return
        if (structure.state == StructureState.OPEN) structure.state = StructureState.CLOSING
        for (request in group.requests) {
            val leg = structure.legs.firstOrNull { it.symbol == request.symbol } ?: continue
            leg.beginClose(request.id, request.quantity)
            owners[request.id] = Owner(structure, leg, opening = false)
        }
    }

    /**
     * Order [orderId] filled a slice of [quantity] at [price], its last one unless [final] is false (a partial
     * fill: the order works on); returns what it belonged to, or null.
     */
    fun filled(
        orderId: String,
        quantity: BigDecimal,
        price: BigDecimal,
        final: Boolean = true,
    ): Owner? {
        val owner = (if (final) owners.remove(orderId) else owners[orderId]) ?: return null
        val (structure, leg, opening) = owner
        if (opening) {
            leg.open(quantity, price, final)
            if (structure.state == StructureState.PENDING && structure.legs.all { it.openEnded }) {
                structure.state = StructureState.OPEN
                publish(structure.opened(strategyId))
            }
        } else {
            leg.closeSlice(orderId, quantity, final)
            leg.realize(quantity, price)
            structure.exit = StructureOutcome.CLOSED
        }
        reopenIfIdle(structure)
        forgetIfDone(structure)
        return owner
    }

    /**
     * Order [orderId] ended without a fill (cancelled or rejected by the venue). A closing structure
     * left with nothing working is OPEN again, so its `CLOSE` can be tried again.
     */
    fun ended(orderId: String): Owner? {
        val owner = owners.remove(orderId) ?: return null
        val structure = owner.structure
        if (owner.opening) owner.leg.endOpen() else owner.leg.endClose(orderId)
        reopenIfIdle(structure)
        forgetIfDone(structure)
        return owner
    }

    /**
     * The engine refused [orderId]: a refused opening group sent nothing and is dropped; a leg of a
     * structure already unwinding, or a closing leg, simply ended.
     */
    fun refused(orderId: String) {
        val owner = owners[orderId] ?: return
        if (!owner.opening || owner.structure.state != StructureState.PENDING) {
            ended(orderId)
            return
        }
        val structure = owner.structure
        structure.legs.forEach { owners.remove(it.openOrderId) }
        byAlias.remove(structure.alias)
        byId.remove(structure.id)
    }

    /** [structure]'s legs are being unwound after one failed. */
    fun unwinding(structure: LiveStructure) {
        structure.state = StructureState.UNWINDING
    }

    /**
     * A fill on [symbol] that is no structure's own order (a flatten, a stop that flattens): it closes
     * legs held on the other side, oldest structure first, so the book never holds what the account
     * no longer does. A fill on the same side as a leg opens nothing here.
     */
    fun external(
        symbol: String,
        side: Side,
        quantity: BigDecimal,
        price: BigDecimal,
    ) {
        var left = quantity
        for (structure in byId.values.toList()) {
            if (left.signum() == 0) return
            for (leg in structure.legs) {
                if (leg.symbol != symbol || leg.side == side || leg.held.signum() == 0 || left.signum() == 0) continue
                val closed = left.min(leg.held)
                leg.realize(closed, price)
                structure.exit = StructureOutcome.CLOSED
                left = left.subtract(closed)
            }
            forgetIfDone(structure)
        }
    }

    /** A closing structure left with nothing working is OPEN again, so its `CLOSE` can be tried again. */
    private fun reopenIfIdle(structure: LiveStructure) {
        if (structure.state == StructureState.CLOSING && structure.legs.none { it.isClosing }) {
            structure.state = StructureState.OPEN
        }
    }

    /** Drops [structure] once nothing of it is held or working. */
    internal fun forgetIfDone(structure: LiveStructure) {
        val idle = structure.legs.all { it.openEnded && it.held.signum() == 0 && !it.isClosing }
        if (!idle) return
        byAlias.remove(structure.alias)
        byId.remove(structure.id)
        publish(structure.closed(strategyId))
    }
}
