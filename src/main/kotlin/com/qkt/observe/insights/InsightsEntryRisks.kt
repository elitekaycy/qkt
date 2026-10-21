package com.qkt.observe.insights

import com.qkt.execution.OrderRequest
import com.qkt.execution.StopLossSpec
import java.math.BigDecimal
import java.util.Collections

/**
 * The initial stop, target and risk of each position qkt opened with a bracket: the bracket's levels
 * when it is submitted, replaced by the protective orders qkt actually places (`<bracket>-sl`,
 * `<bracket>-tp`, re-anchored to the fill), and the risk the entry fill took in account currency
 * (`|entry - stop| x quantity x value per point`, [InsightsVenues.valuePerPoint]). Kept by entry order
 * id, then by the id of the position the entry opened; the oldest of [capacity] are forgotten.
 */
class InsightsEntryRisks(
    private val venues: InsightsVenues,
    capacity: Int = 10_000,
) {
    private data class EntryRisk(
        val orderId: String,
        val stop: BigDecimal?,
        val target: BigDecimal?,
        val risk: BigDecimal?,
    )

    private val brackets = bounded<String, Pair<BigDecimal?, BigDecimal?>>(capacity)
    private val bracketEntries = bounded<String, String>(capacity)
    private val risks = bounded<String, EntryRisk>(capacity)

    /** An order was submitted: a bracket's levels, or one of the protective orders qkt placed for one. */
    fun submitted(request: OrderRequest) {
        when (request) {
            is OrderRequest.Bracket -> {
                bracketEntries[request.id] = request.entry.id
                brackets[request.entry.id] = (request.stopLoss as? StopLossSpec.Fixed)?.price to request.takeProfit
            }
            is OrderRequest.Stop -> protective(request.id, request.stopPrice, stop = true)
            is OrderRequest.Limit -> protective(request.id, request.limitPrice, stop = false)
            else -> Unit
        }
    }

    /**
     * Entry order [orderId] filled [quantity] at [price] into position [positionId]: its levels and risk,
     * or null when it had no bracket. A later fill of the same order adds its risk; another order adding
     * to the position leaves the initial levels as they were.
     */
    fun entered(
        positionId: String,
        orderId: String,
        symbol: String,
        price: BigDecimal,
        quantity: BigDecimal,
    ): Map<String, Any?>? {
        val prior = risks[positionId]
        val (stop, target) = brackets[orderId] ?: return prior?.let(::fieldsOf)
        if (prior != null && prior.orderId != orderId) return fieldsOf(prior)
        val point = venues.valuePerPoint(symbol)
        val add = stop?.let { s -> point?.let { (price - s).abs() * quantity * it } }
        val risk = if (prior?.risk != null && add != null) prior.risk + add else add
        return fieldsOf(EntryRisk(orderId, stop, target, risk).also { risks[positionId] = it })
    }

    /** `initialStop`, `initialTarget` and `riskAmount` of position [positionId]; empty when its entry had no bracket. */
    fun fields(positionId: String): Map<String, Any?> = risks[positionId]?.let(::fieldsOf) ?: emptyMap()

    private fun fieldsOf(r: EntryRisk): Map<String, Any?> =
        mapOf("initialStop" to r.stop, "initialTarget" to r.target, "riskAmount" to r.risk)

    private fun protective(
        id: String,
        price: BigDecimal,
        stop: Boolean,
    ) {
        val bracket = id.removeSuffix(if (stop) "-sl" else "-tp").takeIf { it != id } ?: return
        val entry = bracketEntries[bracket] ?: return
        val (s, t) = brackets[entry] ?: (null to null)
        brackets[entry] = if (stop) price to t else s to price
    }

    internal companion object {
        /** A thread-safe map that forgets its oldest entry beyond [capacity]. */
        fun <K, V> bounded(capacity: Int): MutableMap<K, V> =
            Collections.synchronizedMap(
                object : LinkedHashMap<K, V>(16, 0.75f, false) {
                    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>): Boolean = size > capacity
                },
            )
    }
}
