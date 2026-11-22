package com.qkt.broker

import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Tick
import java.math.BigDecimal

/**
 * The order in which one print fills the resting orders it reaches together: the order nearest the price the move
 * started from fills first, as the market would have crossed it first (docs/reference/dsl/actions.md, OCO_ENTRY
 * "Same-bar dual breach"). That reference is the previous tick's price on a tick replay, and the bar's open on a bar
 * replayed without ticks. Orders at the same distance fill in the order they were submitted.
 */
internal class TriggerSequence(
    private val barReplayed: (String) -> Boolean,
) {
    private val submitted: MutableMap<String, Long> = HashMap()
    private val barOpen: MutableMap<String, BigDecimal> = HashMap()
    private var next = 0L

    /** Records [request]'s submission rank, the tie-break between orders at the same distance. */
    fun admit(request: OrderRequest) {
        submitted[request.id] = next++
    }

    /** Drops [orderId] once it no longer rests. */
    fun forget(orderId: String) {
        submitted.remove(orderId)
    }

    /** Tracks each replayed bar's open: its first print follows the previous bar's close (the print with volume). */
    fun observe(
        tick: Tick,
        previous: Tick?,
    ) {
        if (barReplayed(tick.symbol) && (previous == null || previous.volume != null)) {
            barOpen[tick.symbol] = tick.price
        }
    }

    /** Sorts [triggered], all reached by [tick], nearest the reference price first. */
    fun order(
        triggered: MutableList<OrderRequest>,
        tick: Tick,
        previous: Tick?,
    ) {
        if (triggered.size < 2) return
        val reference = (if (barReplayed(tick.symbol)) barOpen[tick.symbol] else previous?.price) ?: return
        val byDistance = compareBy<OrderRequest> { level(it).subtract(reference).abs() }
        triggered.sortWith(byDistance.thenBy { submitted[it.id] ?: Long.MAX_VALUE })
    }

    // Only these four rest in the paper broker's working list (PaperBroker.submit refuses any other type).
    private fun level(request: OrderRequest): BigDecimal =
        when (request) {
            is OrderRequest.Limit -> request.limitPrice
            is OrderRequest.Stop -> request.stopPrice
            is OrderRequest.StopLimit -> request.stopPrice
            else -> (request as OrderRequest.IfTouched).triggerPrice
        }
}
