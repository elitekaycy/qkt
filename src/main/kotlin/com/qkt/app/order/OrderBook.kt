package com.qkt.app.order

import com.qkt.execution.ManagedOrder
import com.qkt.execution.isTerminal

/**
 * Every order the engine manages, plus the live-order indexes the per-tick scan relies on.
 *
 * [orders] keeps insertion order, and every index is maintained in that same order, so a scan
 * over [liveIdsFor] visits orders exactly as a full scan of [orders] would. The indexes exist
 * for cost, not meaning: a tick touches O(this symbol's live orders) and the GTD sweep touches
 * only deadline-bearing orders, instead of every order ever created.
 *
 * Writes go through [put] or [evict] so the indexes never drift from [orders]. Single engine
 * thread; not thread-safe.
 */
internal class OrderBook(
    private val isReferenced: (String) -> Boolean,
    private val reclaim: (String) -> Unit,
) {
    private val byId: LinkedHashMap<String, ManagedOrder> = LinkedHashMap()

    /** Live order ids bucketed by symbol, in [orders] insertion order. */
    private val liveBySymbol: MutableMap<String, LinkedHashSet<String>> = mutableMapOf()

    /** Live orders carrying a GTD deadline, id -> deadline epoch ms, in insertion order. */
    private val gtdLive: LinkedHashMap<String, Long> = LinkedHashMap()

    /** Terminal ids awaiting reclamation, drained once per tick by [drainGc]. */
    private val gcQueue: ArrayDeque<String> = ArrayDeque()

    /** Read-only view of every tracked order, in insertion order. */
    val orders: Map<String, ManagedOrder> get() = byId

    /** Live orders with a GTD deadline, id -> deadline epoch ms. */
    val gtdDeadlines: Map<String, Long> get() = gtdLive

    operator fun get(id: String): ManagedOrder? = byId[id]

    operator fun contains(id: String): Boolean = byId.containsKey(id)

    /** Live order ids for [symbol], or null when none was ever live there. */
    fun liveIdsFor(symbol: String): Set<String>? = liveBySymbol[symbol]

    /** Stores [managed] (inserting or replacing) and re-indexes it by its current state. */
    fun put(managed: ManagedOrder) {
        byId[managed.id] = managed
        index(managed)
    }

    /** Removes [id] and every index entry for it. */
    fun evict(id: String) {
        val symbol = byId[id]?.request?.symbol
        byId.remove(id)
        if (symbol != null) liveBySymbol[symbol]?.remove(id)
        gtdLive.remove(id)
    }

    /** Queues [id] for reclamation once it has gone terminal. */
    fun enqueueGc(id: String) {
        gcQueue.addLast(id)
    }

    /**
     * Visits each queued id once: [reclaim] runs for a terminal, unreferenced order; a still
     * [isReferenced] one is re-queued for a later pass. Only dead orders are touched, so this
     * can never change a trading decision.
     */
    fun drainGc() {
        repeat(gcQueue.size) {
            val id = gcQueue.removeFirst()
            val managed = byId[id]
            when {
                managed == null -> Unit
                !managed.state.isTerminal -> Unit
                isReferenced(id) -> gcQueue.addLast(id)
                else -> reclaim(id)
            }
        }
    }

    // `expiresAt` is fixed at creation, so re-indexing an order whose state changed keeps the
    // deadline subset correct.
    private fun index(managed: ManagedOrder) {
        val symbol = managed.request.symbol
        val id = managed.id
        if (managed.state.isTerminal) {
            liveBySymbol[symbol]?.remove(id)
            gtdLive.remove(id)
        } else {
            liveBySymbol.getOrPut(symbol) { LinkedHashSet() }.add(id)
            managed.request.expiresAt?.let { gtdLive[id] = it }
        }
    }
}
