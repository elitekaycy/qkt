package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** Decodes a persisted market order. */
internal fun OrderRequestDto.toMarket(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.Market =
    OrderRequest.Market(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        closesTicket = closesTicket,
        closesLegId = closesLegId,
        partialClose = partialClose,
    )

/** Decodes a persisted limit order. */
internal fun OrderRequestDto.toLimit(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.Limit =
    OrderRequest.Limit(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        limitPrice = BigDecimal(requireNotNull(limitPrice) { "Limit DTO missing limitPrice" }),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Decodes a persisted stop order. */
internal fun OrderRequestDto.toStop(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.Stop =
    OrderRequest.Stop(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        stopPrice = BigDecimal(requireNotNull(stopPrice) { "Stop DTO missing stopPrice" }),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Decodes a persisted stop-limit order. */
internal fun OrderRequestDto.toStopLimit(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.StopLimit =
    OrderRequest.StopLimit(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        stopPrice =
            BigDecimal(
                requireNotNull(stopPrice) { "StopLimit DTO missing stopPrice" },
            ),
        limitPrice =
            BigDecimal(
                requireNotNull(limitPrice) { "StopLimit DTO missing limitPrice" },
            ),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Encodes a market order. */
internal fun encodeMarket(req: OrderRequest.Market): OrderRequestDto =
    OrderRequestDto(
        type = "Market",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        closesTicket = req.closesTicket,
        closesLegId = req.closesLegId,
        partialClose = req.partialClose,
    )

/** Encodes a limit order. */
internal fun encodeLimit(req: OrderRequest.Limit): OrderRequestDto =
    OrderRequestDto(
        type = "Limit",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        limitPrice = req.limitPrice.toPlainString(),
        expiresAt = req.expiresAt,
    )

/** Encodes a stop order. */
internal fun encodeStop(req: OrderRequest.Stop): OrderRequestDto =
    OrderRequestDto(
        type = "Stop",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        stopPrice = req.stopPrice.toPlainString(),
        expiresAt = req.expiresAt,
    )

/** Encodes a stop-limit order. */
internal fun encodeStopLimit(req: OrderRequest.StopLimit): OrderRequestDto =
    OrderRequestDto(
        type = "StopLimit",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        stopPrice = req.stopPrice.toPlainString(),
        limitPrice = req.limitPrice.toPlainString(),
        expiresAt = req.expiresAt,
    )
