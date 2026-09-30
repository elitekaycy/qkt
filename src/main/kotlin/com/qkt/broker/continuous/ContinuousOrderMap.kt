package com.qkt.broker.continuous

import com.qkt.execution.OrderRequest

/**
 * An engine order on a continuous stream, working on contract [contractIndex] under [venueId]; an
 * order re-placed at a roll works under `<engine id>~r<replacements>`.
 */
internal data class ContinuousOrder(
    val request: OrderRequest,
    val venueId: String,
    val contractIndex: Int,
    val replacements: Int = 0,
) {
    /** Whether this is the engine's order as first placed, not a re-placement made at a roll. */
    val isOriginal: Boolean get() = replacements == 0
}

/** The working orders of one continuous stream, by engine id and by the venue id they work under. */
internal class ContinuousOrderMap {
    private val byEngineId = LinkedHashMap<String, ContinuousOrder>()
    private val engineIdByVenueId = HashMap<String, String>()

    fun add(order: ContinuousOrder) {
        byEngineId[order.request.id]?.let { engineIdByVenueId.remove(it.venueId) }
        byEngineId[order.request.id] = order
        engineIdByVenueId[order.venueId] = order.request.id
    }

    fun byEngineId(id: String): ContinuousOrder? = byEngineId[id]

    fun byVenueId(venueId: String): ContinuousOrder? = engineIdByVenueId[venueId]?.let(byEngineId::get)

    /** The orders working on contract [index], oldest first. */
    fun on(index: Int): List<ContinuousOrder> = byEngineId.values.filter { it.contractIndex == index }

    /** Forget the order working under [venueId]; returns it, or null when none does. */
    fun removeByVenueId(venueId: String): ContinuousOrder? {
        val engineId = engineIdByVenueId.remove(venueId) ?: return null
        return byEngineId.remove(engineId)
    }
}
