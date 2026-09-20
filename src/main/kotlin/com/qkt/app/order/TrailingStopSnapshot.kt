package com.qkt.app.order

import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.execution.OrderState
import com.qkt.persistence.PersistedTrailingStop

/**
 * The moving state of every resting engine-held stop in [orders], grouped by strategy, as the
 * persistor stores it. A plain trailing stop that has not seen a price yet has nothing to resume
 * from and is left out.
 */
internal fun trailingStopSnapshot(
    orders: Map<String, ManagedOrder>,
    stops: ManagedStopBook,
): Map<String, List<PersistedTrailingStop>> {
    val result = mutableMapOf<String, MutableList<PersistedTrailingStop>>()
    for ((id, managed) in orders) {
        val request = managed.request
        if (!hasPersistentDynamicState(request) || managed.state != OrderState.PENDING) continue
        val strategyId = request.strategyId
        if (strategyId.isBlank()) continue
        val entryPrice =
            when (request) {
                is OrderRequest.ArmedTrailingStop -> request.entryPrice
                is OrderRequest.SteppedStop -> request.entryPrice
                is OrderRequest.TimeTighteningStop -> request.entryPrice
                is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> stops.hwm(id) ?: continue
                else -> error("unreachable")
            }
        result.getOrPut(strategyId) { mutableListOf() }.add(
            PersistedTrailingStop(
                clientOrderId = id,
                brokerOrderId = managed.brokerOrderId,
                strategyId = strategyId,
                request = request,
                armed = stops.armed(id) ?: false,
                hwm = stops.hwm(id) ?: entryPrice,
                stepIndex = stops.stepIndex(id) ?: 0,
                elapsedIntervals = stops.elapsedIntervals(id) ?: 0L,
                stopLevel = stops.level(id),
            ),
        )
    }
    return result
}
