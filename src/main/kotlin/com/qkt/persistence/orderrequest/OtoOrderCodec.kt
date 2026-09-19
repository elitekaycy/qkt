package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** Decodes a persisted OTO wrapper with its parent and unarmed children. */
internal fun OrderRequestDto.toOto(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.OTO =
    OrderRequest.OTO(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        parent = requireNotNull(parent) { "OTO DTO missing parent" }.toDomain(),
        children = requireNotNull(children) { "OTO DTO missing children" }.map { it.toDomain() },
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Encodes an OTO; its parent and every child must be persistable variants. */
internal fun encodeOto(req: OrderRequest.OTO): OrderRequestDto {
    val parent =
        requireNotNull(OrderRequestDto.fromDomain(req.parent)) {
            "OTO ${req.id} parent ${req.parent::class.simpleName} cannot be persisted"
        }
    val children =
        req.children.map { child ->
            requireNotNull(OrderRequestDto.fromDomain(child)) {
                "OTO ${req.id} child ${child.id} (${child::class.simpleName}) cannot be persisted"
            }
        }
    return OrderRequestDto(
        type = "OTO",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        expiresAt = req.expiresAt,
        parent = parent,
        children = children,
    )
}
