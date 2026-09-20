package com.qkt.persistence.orderrequest

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.execution.withLegIntent
import com.qkt.persistence.LegIntentDto
import java.math.BigDecimal
import kotlinx.serialization.Serializable

/**
 * On-disk shape of one [OrderRequest], shared by every state file that stores orders (pending
 * orders, OCO legs, trailing stops). One flat record carries every variant: [type] names the
 * variant and only that variant's fields are set. Per-variant field mapping lives in the
 * `*OrderCodec` files of this package; this class only dispatches on the variant.
 */
@Serializable
internal data class OrderRequestDto(
    val type: String,
    val id: String,
    val symbol: String,
    val side: String,
    val quantity: String,
    val timeInForce: String,
    val timestamp: Long,
    val strategyId: String = "",
    // Variant-specific fields:
    val closesTicket: String? = null,
    val closesLegId: String? = null,
    val partialClose: Boolean = false,
    val limitPrice: String? = null,
    val stopPrice: String? = null,
    val triggerPrice: String? = null,
    val onTrigger: String? = null,
    val expiresAt: Long? = null,
    val entryPrice: String? = null,
    val trailDistance: String? = null,
    val mfeThreshold: String? = null,
    val initialDistance: String? = null,
    val steps: List<StopStepDto>? = null,
    val tightenBy: String? = null,
    val intervalMs: Long? = null,
    val floorDistance: String? = null,
    val trailAmount: String? = null,
    val trailMode: String? = null,
    val limitOffset: String? = null,
    val entry: OrderRequestDto? = null,
    val parent: OrderRequestDto? = null,
    val children: List<OrderRequestDto>? = null,
    val takeProfit: String? = null,
    val stopLossType: String? = null,
    val takeProfitAst: ChildPriceAstDto? = null,
    val stopLossAst: ChildPriceAstDto? = null,
    val scaleOutLegs: List<ScaleOutLegDto>? = null,
    val legIntent: LegIntentDto? = null,
) {
    fun toDomain(): OrderRequest {
        val shape = toDomainShape()
        val intent = legIntent ?: return shape
        return shape.withLegIntent(intent.toDomain())
    }

    private fun toDomainShape(): OrderRequest {
        val sideEnum = Side.valueOf(side)
        val qty = BigDecimal(quantity)
        val tif = TimeInForce.valueOf(timeInForce)
        return when (type) {
            "Market" -> toMarket(sideEnum, qty, tif)
            "Limit" -> toLimit(sideEnum, qty, tif)
            "Stop" -> toStop(sideEnum, qty, tif)
            "IfTouched" -> toIfTouched(sideEnum, qty, tif)
            "ArmedTrailingStop" -> toArmedTrailingStop(sideEnum, qty, tif)
            "SteppedStop" -> toSteppedStop(sideEnum, qty, tif)
            "TimeTighteningStop" -> toTimeTighteningStop(sideEnum, qty, tif)
            "StopLimit" -> toStopLimit(sideEnum, qty, tif)
            "TrailingStop" -> toTrailingStop(sideEnum, qty, tif)
            "TrailingStopLimit" -> toTrailingStopLimit(sideEnum, qty, tif)
            "Bracket" -> toBracket(sideEnum, qty, tif)
            "OTO" -> toOto(sideEnum, qty, tif)
            "ScaleOut" -> toScaleOut(sideEnum, qty, tif)
            else -> error("Unknown OrderRequest type in persisted state: $type")
        }
    }

    companion object {
        /** Encodes [req], or returns null for composite variants persisted by their own files. */
        fun fromDomain(req: OrderRequest): OrderRequestDto? =
            fromDomainShape(req)?.copy(legIntent = LegIntentDto.from(req.legIntent))

        private fun fromDomainShape(req: OrderRequest): OrderRequestDto? =
            when (req) {
                is OrderRequest.Market -> encodeMarket(req)
                is OrderRequest.Limit -> encodeLimit(req)
                is OrderRequest.Stop -> encodeStop(req)
                is OrderRequest.IfTouched -> encodeIfTouched(req)
                is OrderRequest.ArmedTrailingStop -> encodeArmedTrailingStop(req)
                is OrderRequest.SteppedStop -> encodeSteppedStop(req)
                is OrderRequest.TimeTighteningStop -> encodeTimeTighteningStop(req)
                is OrderRequest.StopLimit -> encodeStopLimit(req)
                is OrderRequest.TrailingStop -> encodeTrailingStop(req)
                is OrderRequest.TrailingStopLimit -> encodeTrailingStopLimit(req)
                is OrderRequest.Bracket -> encodeBracket(req)
                is OrderRequest.OTO -> encodeOto(req)
                is OrderRequest.ScaleOut -> encodeScaleOut(req)
                // Composite variants are persisted by their dedicated paths (OCO legs,
                // bracket pairs, stack tiers), not as flat pending orders — skip them here.
                is OrderRequest.StandaloneOCO,
                is OrderRequest.TimeExit,
                is OrderRequest.Stack,
                -> null
            }
    }
}
