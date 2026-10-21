package com.qkt.broker

import com.qkt.common.Side
import com.qkt.positions.Position
import java.math.BigDecimal

/**
 * How one fill moved a position: its [entry] leg (`IN` opens or adds, `OUT` reduces or closes, `INOUT`
 * reverses through zero), the position it belongs to ([positionId]), and for a reversal the position it
 * opened ([nextPositionId]). Ids are qkt's own for venues that name no position: `<symbol>#<openedAt>`,
 * the position's open time, so a new id begins whenever the position crosses zero and the same id comes
 * back after a restart (the open time is persisted with the position).
 */
data class VenueLeg(
    val entry: String,
    val positionId: String?,
    val nextPositionId: String? = null,
) {
    companion object {
        /** The leg a fill made, moving the position from [before] to [after] (null: flat). */
        fun of(
            before: Position?,
            after: Position?,
        ): VenueLeg {
            val was = before?.quantity ?: BigDecimal.ZERO
            val now = after?.quantity ?: BigDecimal.ZERO
            val entry =
                when {
                    was.signum() == 0 -> "IN"
                    now.signum() == 0 -> "OUT"
                    was.signum() != now.signum() -> "INOUT"
                    now.abs() > was.abs() -> "IN"
                    else -> "OUT"
                }
            val opened = after?.takeIf { now.signum() != 0 }?.let(::idOf)
            return when (entry) {
                "IN" -> VenueLeg(entry, if (was.signum() == 0) opened else before?.let(::idOf))
                "INOUT" -> VenueLeg(entry, before?.let(::idOf), opened)
                else -> VenueLeg(entry, before?.let(::idOf))
            }
        }

        /**
         * The leg a fill of [quantity] on [side] at [timestamp] will make from [before], before qkt's
         * accounting has applied it: a position opened or reversed by it opens at [timestamp].
         */
        fun predict(
            symbol: String,
            before: Position?,
            side: Side,
            quantity: BigDecimal,
            timestamp: Long,
        ): VenueLeg {
            val was = before?.quantity ?: BigDecimal.ZERO
            val now = if (side == Side.BUY) was + quantity else was - quantity
            val continues = was.signum() != 0 && was.signum() == now.signum()
            val openedAt = if (continues) before?.openedAt else timestamp
            return of(before, Position(symbol, now, BigDecimal.ZERO, openedAt))
        }

        /** qkt's id of the position [position] is: its symbol and open time, null without an open time. */
        fun idOf(position: Position): String? = position.openedAt?.let { "${position.symbol}#$it" }
    }
}
