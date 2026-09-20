package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.ScaleOutLeg
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/** Decodes a persisted scale-out with its basis order and legs. */
internal fun OrderRequestDto.toScaleOut(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.ScaleOut =
    OrderRequest.ScaleOut(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        basis = requireNotNull(entry) { "ScaleOut DTO missing basis" }.toDomain(),
        legs =
            requireNotNull(scaleOutLegs) { "ScaleOut DTO missing legs" }
                .map { it.toDomain() },
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Encodes a scale-out; its basis must be a persistable variant. */
internal fun encodeScaleOut(req: OrderRequest.ScaleOut): OrderRequestDto =
    OrderRequestDto(
        type = "ScaleOut",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        expiresAt = req.expiresAt,
        entry =
            requireNotNull(OrderRequestDto.fromDomain(req.basis)) {
                "ScaleOut ${req.id} basis ${req.basis::class.simpleName} cannot be persisted"
            },
        scaleOutLegs = req.legs.map(ScaleOutLegDto::fromDomain),
    )

/** On-disk shape of one scale-out leg: a price target and the fraction it closes. */
@Serializable
internal data class ScaleOutLegDto(
    val priceTarget: String,
    val fraction: String,
) {
    fun toDomain(): ScaleOutLeg =
        ScaleOutLeg(
            priceTarget = BigDecimal(priceTarget),
            fraction = BigDecimal(fraction),
        )

    companion object {
        fun fromDomain(leg: ScaleOutLeg): ScaleOutLegDto =
            ScaleOutLegDto(
                priceTarget = leg.priceTarget.toPlainString(),
                fraction = leg.fraction.toPlainString(),
            )
    }
}
