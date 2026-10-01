package com.qkt.app

import com.qkt.common.Side
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.marketdata.MarketPriceProvider
import com.qkt.strategy.Signal
import com.qkt.strategy.StructureLegPosition
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import com.qkt.strategy.StructureView
import java.math.BigDecimal

/**
 * One strategy's option structures: the single writer of their state, driven by the
 * [StructureCoordinator] from bus events and read by the strategy as its [StructureView]. Each live
 * structure is keyed by its alias. A leg's held quantity grows with its opening fills and shrinks with
 * its closing fills and its expiry settlement, each realizing premium P&L (before fees) at its price. A structure
 * leaves the book once nothing is held and no order of it is working.
 */
internal class StructureBook(
    private val instruments: InstrumentRegistry,
    private val prices: MarketPriceProvider,
) : StructureView {
    /** What an order id belongs to: [leg] of [structure], as its opening order or not. */
    data class Owner(
        val structure: LiveStructure,
        val leg: StructureLeg,
        val opening: Boolean,
    )

    private val byAlias = HashMap<String, LiveStructure>()
    private val byId = HashMap<String, LiveStructure>()
    private val owners = HashMap<String, Owner>()

    override fun live(alias: String): StructurePosition? = byAlias[alias]?.let(::position)

    override fun mark(symbol: String): BigDecimal? = prices.lastPrice(symbol)

    /** Records an accepted [group]: a new PENDING structure, or closing orders for the structure it closes. */
    fun accept(group: Signal.SubmitGroup) {
        val closes = group.closes
        if (closes == null) {
            require(group.alias !in byAlias) { "structure ${group.alias} is already live" }
            val legs =
                group.requests.map { r ->
                    StructureLeg(r.symbol, r.side, r.id, sizeOf(r.symbol), expiryOf(r.symbol))
                }
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

    /** Order [orderId] filled [quantity] at [price]; returns what it belonged to, or null. */
    fun filled(
        orderId: String,
        quantity: BigDecimal,
        price: BigDecimal,
    ): Owner? {
        val owner = owners.remove(orderId) ?: return null
        val (structure, leg, opening) = owner
        if (opening) {
            leg.open(quantity, price)
            if (structure.state == StructureState.PENDING && structure.legs.all { it.openEnded }) {
                structure.state = StructureState.OPEN
            }
        } else {
            leg.endClose(orderId)
            leg.realize(quantity, price)
        }
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
        if (structure.state == StructureState.CLOSING && structure.legs.none { it.isClosing }) {
            structure.state = StructureState.OPEN
        }
        forgetIfDone(structure)
        return owner
    }

    /** The risk engine refused the group holding [orderId]: an opening group sent nothing and is dropped. */
    fun refused(orderId: String) {
        val owner = owners[orderId] ?: return
        if (!owner.opening) {
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

    /** Every structure leg on [symbol] settled at its expiry at [price]. */
    fun settled(
        symbol: String,
        price: BigDecimal,
    ) {
        for (structure in byId.values.toList()) {
            structure.legs.filter { it.symbol == symbol && it.held.signum() > 0 }.forEach { it.realize(it.held, price) }
            forgetIfDone(structure)
        }
    }

    /** Drops [structure] once nothing of it is held or working. */
    private fun forgetIfDone(structure: LiveStructure) {
        val idle = structure.legs.all { it.openEnded && it.held.signum() == 0 && !it.isClosing }
        if (!idle) return
        byAlias.remove(structure.alias)
        byId.remove(structure.id)
    }

    private fun position(s: LiveStructure): StructurePosition =
        StructurePosition(
            s.id,
            s.alias,
            s.state,
            s.size,
            s.legs.map { leg ->
                val sign = if (leg.side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
                StructureLegPosition(
                    leg.symbol,
                    leg.contractSize,
                    leg.expiryMs,
                    leg.opened.multiply(sign),
                    leg.entryPrice,
                    leg.held.multiply(sign),
                    leg.realized,
                )
            },
        )

    private fun sizeOf(symbol: String): BigDecimal =
        requireNotNull(instruments.lookup(symbol)) {
            "$symbol is not catalogued"
        }.contractSize

    private fun expiryOf(symbol: String): Long =
        requireNotNull(
            instruments.lookup(symbol)?.derivative as? OptionTerms,
        ) { "$symbol has no option terms" }.expiryMs
}
