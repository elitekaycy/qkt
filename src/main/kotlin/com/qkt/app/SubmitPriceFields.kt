package com.qkt.app

import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec

/**
 * The price-bearing fields of [request] as a log fragment, e.g. `stopPrice=2350.50` for a stop
 * or `takeProfit=… stopLoss=… entry=Market` for a bracket. Empty for a plain market order.
 * [OrderSubmitter] pairs it with the last seen price in its submit context line.
 */
internal fun submitPriceFields(request: OrderRequest): String =
    when (request) {
        is OrderRequest.Stop -> "stopPrice=${request.stopPrice}"
        is OrderRequest.StopLimit ->
            "stopPrice=${request.stopPrice} limitPrice=${request.limitPrice}"
        is OrderRequest.Limit -> "limitPrice=${request.limitPrice}"
        is OrderRequest.IfTouched ->
            "triggerPrice=${request.triggerPrice}" +
                if (request.limitPrice != null) " limitPrice=${request.limitPrice}" else ""
        is OrderRequest.TrailingStop ->
            "trailAmount=${request.trailAmount} mode=${request.trailMode}"
        is OrderRequest.TrailingStopLimit ->
            "trailAmount=${request.trailAmount} mode=${request.trailMode} limitOffset=${request.limitOffset}"
        is OrderRequest.ArmedTrailingStop ->
            "entry=${request.entryPrice} trail=${request.trailDistance} mfe=${request.mfeThreshold}"
        is OrderRequest.SteppedStop ->
            "entry=${request.entryPrice} initial=${request.initialDistance} steps=${request.steps.size}"
        is OrderRequest.TimeTighteningStop ->
            "entry=${request.entryPrice} initial=${request.initialDistance} " +
                "tighten=${request.tightenBy} everyMs=${request.intervalMs} floor=${request.floorDistance}"
        is OrderRequest.Bracket -> {
            val sl =
                when (val s = request.stopLoss) {
                    is StopLossSpec.Fixed -> "stopLoss=${s.price}"
                    is StopLossSpec.ArmedTrail ->
                        "stopLoss=armed(trail=${s.trailDistance}, mfe=${s.mfeThreshold})"
                    is StopLossSpec.SteppedStop ->
                        "stopLoss=stepped(initial=${s.initialDistance}, steps=${s.steps.size})"
                    is StopLossSpec.TimeTighten ->
                        "stopLoss=time(initial=${s.initialDistance}, tighten=${s.tightenBy}, " +
                            "everyMs=${s.intervalMs}, floor=${s.floorDistance})"
                }
            "takeProfit=${request.takeProfit} $sl entry=${request.entry::class.simpleName}"
        }
        else -> ""
    }
