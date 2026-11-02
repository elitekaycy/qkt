package com.qkt.persistence

import com.qkt.broker.SettledClose
import com.qkt.broker.VenueLeg
import com.qkt.common.Side
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** On-disk shape of a [SettledClose]. */
@Serializable
internal data class SettledCloseDto(
    val contract: String,
    val strategyId: String,
    val side: Side,
    val quantity: String,
    val entry: String,
    val positionId: String?,
    val nextPositionId: String?,
    val grossRealized: String,
    val venueCosts: String,
    val atMs: Long,
) {
    fun toDomain() =
        SettledClose(
            contract,
            strategyId,
            side,
            BigDecimal(quantity),
            VenueLeg(entry, positionId, nextPositionId),
            BigDecimal(grossRealized),
            BigDecimal(venueCosts),
            atMs,
        )

    companion object {
        fun of(c: SettledClose) =
            SettledCloseDto(
                c.contract,
                c.strategyId,
                c.side,
                c.quantity.toPlainString(),
                c.leg.entry,
                c.leg.positionId,
                c.leg.nextPositionId,
                c.grossRealized.toPlainString(),
                c.venueCosts.toPlainString(),
                c.atMs,
            )
    }
}
