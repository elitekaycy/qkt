package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.TrailMode
import com.qkt.execution.TriggerType
import java.math.BigDecimal

/** Decodes a persisted if-touched order. */
internal fun OrderRequestDto.toIfTouched(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.IfTouched =
    OrderRequest.IfTouched(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        triggerPrice =
            BigDecimal(
                requireNotNull(triggerPrice) { "IfTouched DTO missing triggerPrice" },
            ),
        onTrigger =
            TriggerType.valueOf(
                requireNotNull(onTrigger) { "IfTouched DTO missing onTrigger" },
            ),
        limitPrice = limitPrice?.let { BigDecimal(it) },
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
        closesTicket = closesTicket,
        partialClose = partialClose,
    )

/** Decodes a persisted broker-side trailing stop. */
internal fun OrderRequestDto.toTrailingStop(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.TrailingStop =
    OrderRequest.TrailingStop(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        trailAmount =
            BigDecimal(
                requireNotNull(trailAmount) { "TrailingStop DTO missing trailAmount" },
            ),
        trailMode =
            TrailMode.valueOf(
                requireNotNull(trailMode) { "TrailingStop DTO missing trailMode" },
            ),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Decodes a persisted trailing stop-limit. */
internal fun OrderRequestDto.toTrailingStopLimit(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.TrailingStopLimit =
    OrderRequest.TrailingStopLimit(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        trailAmount =
            BigDecimal(
                requireNotNull(trailAmount) { "TrailingStopLimit DTO missing trailAmount" },
            ),
        trailMode =
            TrailMode.valueOf(
                requireNotNull(trailMode) { "TrailingStopLimit DTO missing trailMode" },
            ),
        limitOffset =
            BigDecimal(
                requireNotNull(limitOffset) { "TrailingStopLimit DTO missing limitOffset" },
            ),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Encodes an if-touched order. */
internal fun encodeIfTouched(req: OrderRequest.IfTouched): OrderRequestDto =
    OrderRequestDto(
        type = "IfTouched",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        triggerPrice = req.triggerPrice.toPlainString(),
        onTrigger = req.onTrigger.name,
        limitPrice = req.limitPrice?.toPlainString(),
        expiresAt = req.expiresAt,
        closesTicket = req.closesTicket,
        partialClose = req.partialClose,
    )

/** Encodes a broker-side trailing stop. */
internal fun encodeTrailingStop(req: OrderRequest.TrailingStop): OrderRequestDto =
    OrderRequestDto(
        type = "TrailingStop",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        trailAmount = req.trailAmount.toPlainString(),
        trailMode = req.trailMode.name,
        expiresAt = req.expiresAt,
    )

/** Encodes a trailing stop-limit. */
internal fun encodeTrailingStopLimit(req: OrderRequest.TrailingStopLimit): OrderRequestDto =
    OrderRequestDto(
        type = "TrailingStopLimit",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        trailAmount = req.trailAmount.toPlainString(),
        trailMode = req.trailMode.name,
        limitOffset = req.limitOffset.toPlainString(),
        expiresAt = req.expiresAt,
    )
