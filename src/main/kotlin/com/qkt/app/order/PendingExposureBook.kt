package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.isTerminal
import com.qkt.execution.withStrategyId
import java.math.BigDecimal

/**
 * Exposure that live entry orders would add if they filled, for pre-trade risk checks.
 *
 * One entry per risk-increasing order, keyed by the id its fills arrive under. Legs of one OCO
 * share a group and count once: only one of them can fill. Quantities shrink as partial fills
 * arrive, and an entry leaves when its order goes terminal.
 */
internal class PendingExposureBook(
    private val book: OrderBook,
) {
    private class Entry(
        val request: OrderRequest,
        val groupId: String?,
        var filledQuantity: BigDecimal = BigDecimal.ZERO,
    )

    private val entries: MutableMap<String, Entry> = mutableMapOf()
    private val groupScratch: MutableMap<String, BigDecimal> = mutableMapOf()

    operator fun contains(id: String): Boolean = id in entries

    /** Entries on [side] counted once per group plus each ungrouped order. */
    fun orderCountFor(
        side: Side,
        strategyId: String?,
    ): Int {
        var ungrouped = 0
        val groups = mutableSetOf<String>()
        for ((id, entry) in entries) {
            val request = entry.request
            if (request.side != side) continue
            if (strategyId != null && request.strategyId != strategyId) continue
            if (book[id]?.state?.isTerminal == true) continue
            if (request.quantity.subtract(entry.filledQuantity).signum() <= 0) continue
            val groupId = entry.groupId
            if (groupId == null) ungrouped++ else groups.add(groupId)
        }
        return ungrouped + groups.size
    }

    /** Symbols with unfilled entry quantity. */
    fun symbolsFor(strategyId: String?): Set<String> {
        val out = mutableSetOf<String>()
        for ((id, entry) in entries) {
            val request = entry.request
            if (strategyId != null && request.strategyId != strategyId) continue
            if (book[id]?.state?.isTerminal == true) continue
            if (request.quantity.subtract(entry.filledQuantity).signum() <= 0) continue
            out.add(request.symbol)
        }
        return out
    }

    /** Unfilled entry quantity on [symbol] and [side]; a group contributes its largest leg. */
    fun quantityFor(
        symbol: String,
        side: Side,
        strategyId: String?,
    ): BigDecimal {
        var ungrouped = BigDecimal.ZERO
        groupScratch.clear()
        for ((id, entry) in entries) {
            val request = entry.request
            if (request.symbol != symbol || request.side != side) continue
            if (strategyId != null && request.strategyId != strategyId) continue
            if (book[id]?.state?.isTerminal == true) continue
            val remaining = request.quantity.subtract(entry.filledQuantity).max(BigDecimal.ZERO)
            if (remaining.signum() == 0) continue
            val groupId = entry.groupId
            if (groupId == null) {
                ungrouped = ungrouped.add(remaining)
            } else {
                val prior = groupScratch[groupId]
                if (prior == null || remaining > prior) groupScratch[groupId] = remaining
            }
        }
        return groupScratch.values.fold(ungrouped, BigDecimal::add)
    }

    /** Records [request]; re-registering keeps the existing group and filled quantity. */
    fun register(
        request: OrderRequest,
        groupId: String? = null,
    ) {
        val existing = entries[request.id]
        entries[request.id] =
            Entry(
                request = request,
                groupId = existing?.groupId ?: groupId,
                filledQuantity = existing?.filledQuantity ?: BigDecimal.ZERO,
            )
    }

    /** Drops [id]'s entry and returns the group it belonged to, if any. */
    fun remove(id: String): String? = entries.remove(id)?.groupId

    /** Sets how much of [id] has filled so far. */
    fun recordFill(
        id: String,
        cumulativeFilled: BigDecimal,
    ) {
        entries[id]?.filledQuantity = cumulativeFilled
    }
}

/** The order whose fill adds the exposure: a composite's entry, or the order itself. */
internal fun exposureEntryRequest(request: OrderRequest): OrderRequest =
    when (request) {
        is OrderRequest.Bracket -> request.entry.withStrategyId(request.strategyId)
        is OrderRequest.OTO -> request.parent
        is OrderRequest.ScaleOut -> request.basis
        is OrderRequest.TimeExit -> request.target
        else -> request
    }
