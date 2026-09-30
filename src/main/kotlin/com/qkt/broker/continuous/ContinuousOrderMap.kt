package com.qkt.broker.continuous

import com.qkt.execution.OrderRequest

/** An engine order on a continuous stream, working on contract [contractIndex] under [venueId]. */
internal data class ContinuousOrder(
    val request: OrderRequest,
    val venueId: String,
    val contractIndex: Int,
)

/** The working orders of one continuous stream, by engine id and by the venue id they work under. */
internal class ContinuousOrderMap {
    private val byEngineId = LinkedHashMap<String, ContinuousOrder>()
    private val engineIdByVenueId = HashMap<String, String>()

    fun add(order: ContinuousOrder) {
        byEngineId[order.request.id] = order
        engineIdByVenueId[order.venueId] = order.request.id
    }

    fun byEngineId(id: String): ContinuousOrder? = byEngineId[id]

    fun byVenueId(venueId: String): ContinuousOrder? = engineIdByVenueId[venueId]?.let(byEngineId::get)

    /** Forget the order working under [venueId]; returns it, or null when none does. */
    fun removeByVenueId(venueId: String): ContinuousOrder? {
        val engineId = engineIdByVenueId.remove(venueId) ?: return null
        return byEngineId.remove(engineId)
    }
}
