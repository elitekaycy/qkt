package com.qkt.observe.insights

import com.qkt.app.order.resolveBracketAtFill
import com.qkt.app.order.stopPriceAtEntry
import com.qkt.common.Money
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import java.util.Collections

/**
 * The initial stop, target and risk of each position qkt opened with a bracket, as attached at the
 * venue for that position: the bracket re-anchored on the entry's fill exactly as the engine anchors the
 * venue's SL/TP or its own exit orders (`STOP_LOSS BY 3` is three below the fill, not below the price
 * the bracket was sent at), then the protective orders qkt actually places (`<bracket>-sl`,
 * `<bracket>-tp`) when it places its own. The risk is what the entry took in account currency,
 * `|entry - stop| x quantity x value per point` ([InsightsVenues.valuePerPoint]). Brackets are kept by
 * entry order id in this session; the levels by position in [shared], so every session states them.
 */
class InsightsEntryRisks(
    private val venues: InsightsVenues,
    capacity: Int = 10_000,
    private val shared: PositionFacts = PositionFacts(capacity),
) {
    private val brackets = bounded<String, OrderRequest.Bracket>(capacity)
    private val bracketEntries = bounded<String, String>(capacity)
    private val placed = bounded<String, Pair<BigDecimal?, BigDecimal?>>(capacity)
    private val positions = bounded<String, Pair<String, String>>(capacity)

    /** An order was submitted: a bracket, or one of the protective orders qkt placed for one. */
    fun submitted(request: OrderRequest) {
        when (request) {
            is OrderRequest.Bracket -> {
                bracketEntries[request.id] = request.entry.id
                brackets[request.entry.id] = request
            }
            is OrderRequest.StandaloneOCO -> listOf(request.leg1, request.leg2).forEach(::submitted)
            is OrderRequest.Stop -> protective(request.id, request.stopPrice, stop = true)
            is OrderRequest.Limit -> protective(request.id, request.limitPrice, stop = false)
            else -> Unit
        }
    }

    /**
     * Entry order [orderId] filled [quantity] at [price] into position [positionId]: its levels and risk,
     * or the position's own when the order had no bracket. Slices of one order anchor on their average
     * price, as the engine's exits do; another order adding to the position leaves the initial levels.
     */
    fun entered(
        positionId: String,
        orderId: String,
        symbol: String,
        price: BigDecimal,
        quantity: BigDecimal,
    ): Map<String, Any?>? {
        val prior = shared.entry(symbol, positionId)
        val bracket = brackets[orderId] ?: return prior?.let(::fieldsOf)
        if (prior != null && prior.orderId != orderId) return fieldsOf(prior)
        val total = quantity + (prior?.quantity ?: BigDecimal.ZERO)
        val average = prior?.let { (it.price * it.quantity + price * quantity).divide(total, Money.CONTEXT) } ?: price
        positions[orderId] = symbol to positionId
        return fieldsOf(
            anchored(bracket, orderId, symbol, average, total).also { shared.entry(symbol, positionId, it) },
        )
    }

    /** `initialStop`, `initialTarget` and `riskAmount` of [symbol]'s position [positionId]; empty without a bracket. */
    fun fields(
        symbol: String,
        positionId: String,
    ): Map<String, Any?> = shared.entry(symbol, positionId)?.let(::fieldsOf) ?: emptyMap()

    private fun anchored(
        bracket: OrderRequest.Bracket,
        orderId: String,
        symbol: String,
        price: BigDecimal,
        quantity: BigDecimal,
    ): PositionFacts.Entry {
        val tick = venues.priceStep(symbol)
        val resolved = resolveBracketAtFill(bracket, price, tick)
        val (placedStop, placedTarget) = placed[bracket.id] ?: (null to null)
        val stop = placedStop ?: stopPriceAtEntry(resolved, price, tick)
        val target = placedTarget ?: resolved.takeProfit
        val risk = venues.valuePerPoint(symbol)?.let { (price - stop).abs() * quantity * it }
        return PositionFacts.Entry(orderId, price, quantity, stop, target, risk)
    }

    private fun fieldsOf(r: PositionFacts.Entry): Map<String, Any?> =
        mapOf("initialStop" to r.stop, "initialTarget" to r.target, "riskAmount" to r.risk)

    /** qkt placed its own stop or target for bracket `<id>`: that level is the one attached for the position. */
    private fun protective(
        id: String,
        price: BigDecimal,
        stop: Boolean,
    ) {
        val bracket = id.removeSuffix(if (stop) "-sl" else "-tp").takeIf { it != id } ?: return
        val entry = bracketEntries[bracket] ?: return
        val (s, t) = placed[bracket] ?: (null to null)
        placed[bracket] = if (stop) price to t else s to price
        val (symbol, positionId) = positions[entry] ?: return
        val prior = shared.entry(symbol, positionId)?.takeIf { it.orderId == entry } ?: return
        val request = brackets[entry] ?: return
        shared.entry(symbol, positionId, anchored(request, entry, symbol, prior.price, prior.quantity))
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
