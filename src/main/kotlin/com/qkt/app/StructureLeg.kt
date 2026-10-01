package com.qkt.app

import com.qkt.common.Side
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

    /** What is held and not already being closed. */
    val unclosed: BigDecimal get() = held.subtract(closing.values.fold(BigDecimal.ZERO, BigDecimal::add))

    internal fun open(
        quantity: BigDecimal,
        price: BigDecimal,
    ) {
        opened = quantity
        entryPrice = price
        held = quantity
        openEnded = true
    }

    internal fun endOpen() {
        openEnded = true
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
}

/** The read-only view of [this] structure a strategy sees. */
internal fun LiveStructure.position(): StructurePosition =
    StructurePosition(
        id,
        alias,
        state,
        size,
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
