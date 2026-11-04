package com.qkt.broker.continuous

import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.derivatives.futures.ContinuousChain
import com.qkt.derivatives.futures.PriceSpace
import com.qkt.execution.OrderRequest

/**
 * A stream's engine orders onto the contract it follows: refused for a strategy stopped on the stream ([stops]) or a
 * contract that cannot be priced or does not take the order's kind, else translated, recorded in [orders] and sent to
 * [venue].
 */
internal class LaneSubmissions(
    private val bus: EventBus,
    private val chainOf: () -> ContinuousChain,
    private val venue: ContractVenue,
    private val orders: ContinuousOrderMap,
    private val stops: Map<String, String>,
    private val space: (Int) -> PriceSpace,
) {
    /** Sends [request] on contract [index] at [now], or refuses it on the bus. */
    fun submit(
        request: OrderRequest,
        index: Int,
        now: Long,
    ): SubmitAck {
        val chain = chainOf()
        stops[request.strategyId]?.let { return bus.refuse(request, it, now) }
        val space =
            try {
                space(index)
            } catch (e: IllegalArgumentException) {
                return bus.refuse(request, e.message ?: "${chain.symbol} cannot price contract $index", now)
            }
        val translated =
            toContract(request, request.id, chain.contractSymbol(index), space)
                ?: return bus.refuse(
                    request,
                    "${chain.symbol} does not accept ${request::class.simpleName} orders",
                    now,
                )
        orders.add(ContinuousOrder(request, request.id, index))
        return venue.broker.submit(translated)
    }
}
