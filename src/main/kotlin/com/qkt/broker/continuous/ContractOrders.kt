package com.qkt.broker.continuous

import com.qkt.derivatives.futures.PriceSpace
import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * [request] as the same order for [quantity] on [contract] under [venueId], its levels mapped through
 * [space]; null for other shapes.
 */
internal fun toContract(
    request: OrderRequest,
    venueId: String,
    contract: String,
    space: PriceSpace,
    quantity: BigDecimal = request.quantity,
): OrderRequest? =
    when (request) {
        is OrderRequest.Market -> request.copy(id = venueId, symbol = contract, quantity = quantity)
        is OrderRequest.Limit ->
            request.copy(
                id = venueId,
                symbol = contract,
                quantity = quantity,
                limitPrice = space.limitToContract(request.limitPrice, request.side),
            )
        is OrderRequest.Stop ->
            request.copy(
                id = venueId,
                symbol = contract,
                quantity = quantity,
                stopPrice = space.stopToContract(request.stopPrice, request.side),
            )
        is OrderRequest.StopLimit ->
            request.copy(
                id = venueId,
                symbol = contract,
                quantity = quantity,
                stopPrice = space.stopToContract(request.stopPrice, request.side),
                limitPrice = space.limitToContract(request.limitPrice, request.side),
            )
        else -> null
    }
