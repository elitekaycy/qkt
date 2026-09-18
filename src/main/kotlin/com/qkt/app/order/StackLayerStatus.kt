package com.qkt.app.order

import com.qkt.app.OrderManager
import com.qkt.app.StackTracker
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState

/** Engine-held stack layers still waiting for their trigger, for status reporting. */
internal fun pendingStackLayerInfos(
    book: OrderBook,
    stacks: StackTracker,
): List<OrderManager.PendingStackLayerInfo> =
    stacks.all().flatMap { state ->
        state.pendingLayerIds.mapNotNull { layerId ->
            val managed = book[layerId] ?: return@mapNotNull null
            if (managed.state != OrderState.PENDING) return@mapNotNull null
            val triggerPrice =
                when (val r = managed.request) {
                    is OrderRequest.Stop -> r.stopPrice
                    is OrderRequest.Limit -> r.limitPrice
                    else -> return@mapNotNull null
                }
            OrderManager.PendingStackLayerInfo(
                stackId = state.id,
                layer = layerId.substringAfterLast("-l").toIntOrNull() ?: 0,
                triggerPrice = triggerPrice,
                side = managed.request.side.name,
                quantity = managed.request.quantity,
            )
        }
    }
