package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** Decodes a persisted bracket: its entry, take-profit, stop-loss spec and child-price ASTs. */
internal fun OrderRequestDto.toBracket(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.Bracket {
    val stopLoss = toBracketStopLoss()
    return OrderRequest.Bracket(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        entry = requireNotNull(entry) { "Bracket DTO missing entry" }.toDomain(),
        takeProfit =
            BigDecimal(
                requireNotNull(takeProfit) { "Bracket DTO missing takeProfit" },
            ),
        stopLoss = stopLoss,
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
        takeProfitAst = takeProfitAst?.toDomain(),
        stopLossAst = stopLossAst?.toDomain(),
    )
}

/** Encodes a bracket; its entry must itself be a persistable variant. */
internal fun encodeBracket(req: OrderRequest.Bracket): OrderRequestDto {
    val stopFields = bracketStopFields(req.stopLoss)
    return OrderRequestDto(
        type = "Bracket",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        expiresAt = req.expiresAt,
        entry =
            requireNotNull(OrderRequestDto.fromDomain(req.entry)) {
                "Bracket entry ${req.entry::class.simpleName} cannot be persisted"
            },
        takeProfit = req.takeProfit.toPlainString(),
        stopLossType = stopFields.type,
        stopPrice = stopFields.stopPrice,
        trailDistance = stopFields.trailDistance,
        mfeThreshold = stopFields.mfeThreshold,
        initialDistance = stopFields.initialDistance,
        steps = stopFields.steps,
        tightenBy = stopFields.tightenBy,
        intervalMs = stopFields.intervalMs,
        floorDistance = stopFields.floorDistance,
        takeProfitAst = req.takeProfitAst?.let(ChildPriceAstDto::fromDomain),
        stopLossAst = req.stopLossAst?.let(ChildPriceAstDto::fromDomain),
    )
}
