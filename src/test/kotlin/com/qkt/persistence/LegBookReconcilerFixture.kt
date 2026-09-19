package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.LegRole
import com.qkt.positions.Position
import com.qkt.positions.PositionLeg
import java.math.BigDecimal

abstract class LegBookReconcilerFixture {
    protected fun book(vararg legs: PositionLeg): LegBook =
        LegBook(legs.first().symbol).apply { legs.forEach { add(it) } }

    protected fun primary(
        id: String = "leg-1",
        symbol: String = "XAUUSDm",
        side: Side = Side.BUY,
        qty: String = "0.20",
        entry: String = "4700",
    ) = PositionLeg(
        legId = id,
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.PRIMARY,
    )

    protected fun stack(
        id: String = "leg-2",
        parent: String = "leg-1",
        symbol: String = "XAUUSDm",
        side: Side = Side.BUY,
        qty: String = "0.06",
        entry: String = "4710",
    ) = PositionLeg(
        legId = id,
        symbol = symbol,
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.STACK,
        parentLegId = parent,
    )

    protected fun pos(
        symbol: String = "XAUUSDm",
        signedQty: String = "0.20",
        entry: String = "4700",
    ) = Position(symbol = symbol, quantity = BigDecimal(signedQty), avgEntryPrice = BigDecimal(entry))

    protected fun ticketed(
        id: String,
        ticket: String,
        qty: String = "0.20",
        entry: String = "4700",
        side: Side = Side.BUY,
    ) = PositionLeg(
        legId = id,
        symbol = "XAUUSDm",
        side = side,
        quantity = BigDecimal(qty),
        entryPrice = BigDecimal(entry),
        openedAt = 0L,
        role = LegRole.INDEPENDENT,
        brokerTicket = ticket,
    )
}
