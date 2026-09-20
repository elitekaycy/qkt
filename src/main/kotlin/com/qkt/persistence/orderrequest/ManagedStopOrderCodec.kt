package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import com.qkt.execution.TimeInForce
import java.math.BigDecimal

/** Decodes a persisted armed trailing stop. */
internal fun OrderRequestDto.toArmedTrailingStop(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.ArmedTrailingStop =
    OrderRequest.ArmedTrailingStop(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        entryPrice =
            BigDecimal(
                requireNotNull(entryPrice) { "ArmedTrailingStop DTO missing entryPrice" },
            ),
        trailDistance =
            BigDecimal(
                requireNotNull(trailDistance) { "ArmedTrailingStop DTO missing trailDistance" },
            ),
        mfeThreshold =
            BigDecimal(
                requireNotNull(mfeThreshold) { "ArmedTrailingStop DTO missing mfeThreshold" },
            ),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Decodes a persisted stepped stop. */
internal fun OrderRequestDto.toSteppedStop(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.SteppedStop =
    OrderRequest.SteppedStop(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        entryPrice =
            BigDecimal(
                requireNotNull(entryPrice) { "SteppedStop DTO missing entryPrice" },
            ),
        initialDistance =
            BigDecimal(
                requireNotNull(initialDistance) { "SteppedStop DTO missing initialDistance" },
            ),
        steps =
            requireNotNull(steps) { "SteppedStop DTO missing steps" }.map {
                StopLossSpec.Step(
                    mfeThreshold = BigDecimal(it.mfeThreshold),
                    profitDistance = BigDecimal(it.profitDistance),
                )
            },
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Decodes a persisted time-tightening stop. */
internal fun OrderRequestDto.toTimeTighteningStop(
    sideEnum: Side,
    qty: BigDecimal,
    tif: TimeInForce,
): OrderRequest.TimeTighteningStop =
    OrderRequest.TimeTighteningStop(
        id = id,
        symbol = symbol,
        side = sideEnum,
        quantity = qty,
        entryPrice =
            BigDecimal(
                requireNotNull(entryPrice) { "TimeTighteningStop DTO missing entryPrice" },
            ),
        initialDistance =
            BigDecimal(
                requireNotNull(initialDistance) { "TimeTighteningStop DTO missing initialDistance" },
            ),
        tightenBy =
            BigDecimal(
                requireNotNull(tightenBy) { "TimeTighteningStop DTO missing tightenBy" },
            ),
        intervalMs = requireNotNull(intervalMs) { "TimeTighteningStop DTO missing intervalMs" },
        floorDistance =
            BigDecimal(
                requireNotNull(floorDistance) { "TimeTighteningStop DTO missing floorDistance" },
            ),
        timeInForce = tif,
        timestamp = timestamp,
        strategyId = strategyId,
        expiresAt = expiresAt,
    )

/** Encodes an armed trailing stop. */
internal fun encodeArmedTrailingStop(req: OrderRequest.ArmedTrailingStop): OrderRequestDto =
    OrderRequestDto(
        type = "ArmedTrailingStop",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        entryPrice = req.entryPrice.toPlainString(),
        trailDistance = req.trailDistance.toPlainString(),
        mfeThreshold = req.mfeThreshold.toPlainString(),
        expiresAt = req.expiresAt,
    )

/** Encodes a stepped stop. */
internal fun encodeSteppedStop(req: OrderRequest.SteppedStop): OrderRequestDto =
    OrderRequestDto(
        type = "SteppedStop",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        entryPrice = req.entryPrice.toPlainString(),
        initialDistance = req.initialDistance.toPlainString(),
        steps =
            req.steps.map {
                StopStepDto(
                    mfeThreshold = it.mfeThreshold.toPlainString(),
                    profitDistance = it.profitDistance.toPlainString(),
                )
            },
        expiresAt = req.expiresAt,
    )

/** Encodes a time-tightening stop. */
internal fun encodeTimeTighteningStop(req: OrderRequest.TimeTighteningStop): OrderRequestDto =
    OrderRequestDto(
        type = "TimeTighteningStop",
        id = req.id,
        symbol = req.symbol,
        side = req.side.name,
        quantity = req.quantity.toPlainString(),
        timeInForce = req.timeInForce.name,
        timestamp = req.timestamp,
        strategyId = req.strategyId,
        entryPrice = req.entryPrice.toPlainString(),
        initialDistance = req.initialDistance.toPlainString(),
        tightenBy = req.tightenBy.toPlainString(),
        intervalMs = req.intervalMs,
        floorDistance = req.floorDistance.toPlainString(),
        expiresAt = req.expiresAt,
    )
