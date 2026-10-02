package com.qkt.broker.continuous

import com.qkt.execution.OrderRequest
import java.math.BigDecimal

/**
 * An engine order on a continuous stream, working on contract [contractIndex] under [venueId] in a venue
 * order [placed] for that quantity, with [filled] of the engine's order executed so far across every
 * venue order it worked under; an order re-placed at a roll works under `<engine id>~r<replacements>`.
 * [cancelRequested] marks one the engine cancelled while its roll cancel was out: it is not re-placed.
 */
internal data class ContinuousOrder(
    val request: OrderRequest,
    val venueId: String,
    val contractIndex: Int,
    val replacements: Int = 0,
    val placed: BigDecimal = request.quantity,
    val filled: BigDecimal = BigDecimal.ZERO,
    val cancelRequested: Boolean = false,
) {
    /** Whether this is the engine's order as first placed, not a re-placement made at a roll. */
    val isOriginal: Boolean get() = replacements == 0

    /** What is left of the engine's order to fill. */
    val remaining: BigDecimal get() = request.quantity - filled

    /** How much of the venue order it works under has filled. */
    val venueFilled: BigDecimal get() = placed - remaining
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

    /** Every working order, oldest first. */
    val all: List<ContinuousOrder> get() = byEngineId.values.toList()

    fun byVenueId(venueId: String): ContinuousOrder? = engineIdByVenueId[venueId]?.let(byEngineId::get)

    /** The orders working on contract [index], oldest first. */
    fun on(index: Int): List<ContinuousOrder> = byEngineId.values.filter { it.contractIndex == index }

    /** Adds a slice of [quantity] to the order working under [venueId]; returns it updated, or null when none does. */
    fun fill(
        venueId: String,
        quantity: BigDecimal,
    ): ContinuousOrder? = byVenueId(venueId)?.let { it.copy(filled = it.filled + quantity) }?.also(::add)

    /** Forget the order working under [venueId]; returns it, or null when none does. */
    fun removeByVenueId(venueId: String): ContinuousOrder? {
        val engineId = engineIdByVenueId.remove(venueId) ?: return null
        return byEngineId.remove(engineId)
    }
}
