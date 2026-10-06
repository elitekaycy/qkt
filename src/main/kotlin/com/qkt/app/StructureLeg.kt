package com.qkt.app

import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.StructureClosed
import com.qkt.events.StructureOpened
import com.qkt.events.StructureOutcome
import com.qkt.execution.OrderRequest
import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.OptionTerms
import com.qkt.strategy.StructureLegPosition
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import java.math.BigDecimal

/** One leg of a [LiveStructure], opened by [openOrderId] on [side]; quantities are unsigned and signed by [side] when read. */
internal class StructureLeg(
    val symbol: String,
    val side: Side,
    val openOrderId: String,
    val contractSize: BigDecimal,
    val expiryMs: Long,
) {
    var opened: BigDecimal = BigDecimal.ZERO
        private set
    var entryPrice: BigDecimal? = null
        private set
    var held: BigDecimal = BigDecimal.ZERO
        private set
    var realized: BigDecimal = BigDecimal.ZERO
        private set
    var openEnded = false
        private set
    private val closing = HashMap<String, BigDecimal>()

    /** True while a closing order of this leg is working. */
    val isClosing: Boolean get() = closing.isNotEmpty()

    /** The closing orders still working: quantity by order id. */
    val closingOrders: Map<String, BigDecimal> get() = closing

    /** What is held and not already being closed. */
    val unclosed: BigDecimal get() = held.subtract(closing.values.fold(BigDecimal.ZERO, BigDecimal::add))

    /** Opens [quantity] more at [price], entering at the average of every slice; [final] is the order's last slice. */
    internal fun open(
        quantity: BigDecimal,
        price: BigDecimal,
        final: Boolean = true,
    ) {
        val total = opened.add(quantity)
        entryPrice = entryPrice?.multiply(opened)?.add(price.multiply(quantity))?.divide(total, Money.CONTEXT) ?: price
        opened = total
        held = held.add(quantity)
        if (final) openEnded = true
    }

    internal fun endOpen() {
        openEnded = true
    }

    /** Takes back the state a restart restored. */
    internal fun restore(
        opened: BigDecimal,
        entryPrice: BigDecimal?,
        held: BigDecimal,
        realized: BigDecimal,
        openEnded: Boolean,
        closing: Map<String, BigDecimal>,
    ) {
        this.opened = opened
        this.entryPrice = entryPrice
        this.held = held
        this.realized = realized
        this.openEnded = openEnded
        this.closing.clear()
        this.closing.putAll(closing)
    }

    internal fun beginClose(
        orderId: String,
        quantity: BigDecimal,
    ) {
        closing[orderId] = quantity
    }

    internal fun endClose(orderId: String) {
        closing.remove(orderId)
    }

    /** Closing order [orderId] filled a slice of [quantity]: it works on for the rest unless [final]. */
    internal fun closeSlice(
        orderId: String,
        quantity: BigDecimal,
        final: Boolean,
    ) {
        if (final) return endClose(orderId)
        closing.computeIfPresent(orderId) { _, working -> working.subtract(quantity).max(BigDecimal.ZERO) }
    }

    /** Closes [quantity] at [price], never more than is held: a close that overshoots is the account's, not the leg's. */
    internal fun realize(
        quantity: BigDecimal,
        price: BigDecimal,
    ) {
        val closed = quantity.min(held)
        val sign = if (side == Side.BUY) BigDecimal.ONE else BigDecimal.ONE.negate()
        val entry = requireNotNull(entryPrice) { "$symbol closed before it was opened" }
        realized = realized.add(sign.multiply(closed).multiply(contractSize).multiply(price.subtract(entry)))
        held = held.subtract(closed)
    }
}

/** A live structure in the [StructureBook]: PENDING → OPEN ⇄ CLOSING, or PENDING → UNWINDING when a leg fails. */
internal class LiveStructure(
    val id: String,
    val alias: String,
    val size: BigDecimal,
    val legs: List<StructureLeg>,
) {
    var state = StructureState.PENDING
        internal set

    /** How its legs last left: closed by orders, or settled at expiry. */
    var exit = StructureOutcome.CLOSED
        internal set
}

/** The read-only view of [this] structure a strategy sees. */
internal fun LiveStructure.position(): StructurePosition =
    StructurePosition(
        id,
        alias,
        state,
        // What was sent while it opens; what the legs filled once open (a portfolio book scale resizes every leg alike).
        if (state == StructureState.PENDING) size else legs.maxOf { it.opened }.takeIf { it.signum() > 0 } ?: size,
        legs.map { leg ->
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
        legs.any { !it.openEnded || it.isClosing },
    )

/** The opening leg [request] places, with its contract's size and expiry from [instruments]. */
internal fun structureLeg(
    request: OrderRequest,
    instruments: InstrumentRegistry,
): StructureLeg {
    val meta = requireNotNull(instruments.lookup(request.symbol)) { "${request.symbol} is not catalogued" }
    val terms = requireNotNull(meta.derivative as? OptionTerms) { "${request.symbol} has no option terms" }
    return StructureLeg(request.symbol, request.side, request.id, meta.contractSize, terms.expiryMs)
}

/** [this] structure, all legs filled, as [strategyId]'s book reports it. */
internal fun LiveStructure.opened(strategyId: String): StructureOpened =
    position().let { StructureOpened(strategyId, id, alias, it.legs, it.credit()) }

/** [this] structure leaving [strategyId]'s book: unwound if it never opened whole, else by how its legs left. */
internal fun LiveStructure.closed(strategyId: String): StructureClosed {
    val outcome = if (state == StructureState.UNWINDING) StructureOutcome.UNWOUND else exit
    val realized = legs.fold(BigDecimal.ZERO) { sum, leg -> sum.add(leg.realized) }
    return StructureClosed(strategyId, id, alias, position().legs, outcome, realized)
}
