package com.qkt.observe.insights

import com.qkt.broker.BrokerDeal

/**
 * One venue deal-history fetch per account per polling cycle, shared by every
 * [BrokerStatePoller] in the process.
 *
 * Each live session runs its own poller and all of them ask the same account for the
 * same window within a few seconds of each other. The first caller fetches and caches;
 * a later *different* caller whose window is covered by a fresh enough cached fetch
 * filters the cached rows instead of hitting the gateway. A caller never reuses its own
 * earlier fetch, so a poller's consecutive cycles always see the venue. A deal booked in
 * the few seconds between two sessions' fetches reaches the later session on its next
 * cycle through the watermark grace. Callers keep their own watermark and attribution —
 * only the HTTP exchange is shared.
 *
 * ```
 * val shared = SharedDealFetch()
 * val deals = shared.deals(this, "123@Demo", fromMs, nowMs) { f, t -> broker.deals(f, t) }
 * ```
 */
class SharedDealFetch(
    private val freshForMs: Long = 5_000L,
) {
    private class Entry(
        val owner: Any,
        val fromMs: Long,
        val toMs: Long,
        val deals: List<BrokerDeal>,
    )

    private val entries = mutableMapOf<String, Entry>()

    /** Deals in `[fromMs, toMs]` for [accountKey], from another owner's fresh fetch when it covers the window. */
    fun deals(
        owner: Any,
        accountKey: String,
        fromMs: Long,
        toMs: Long,
        fetch: (fromMs: Long, toMs: Long) -> List<BrokerDeal>,
    ): List<BrokerDeal> =
        synchronized(entries) {
            val cached = entries[accountKey]
            if (cached != null &&
                cached.owner !== owner &&
                cached.fromMs <= fromMs &&
                toMs - cached.toMs <= freshForMs
            ) {
                return cached.deals.filter { it.ts in fromMs..toMs }
            }
            val fetched = fetch(fromMs, toMs)
            entries[accountKey] = Entry(owner, fromMs, toMs, fetched)
            fetched
        }
}
