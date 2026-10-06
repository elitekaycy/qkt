package com.qkt.derivatives.options.chain

/**
 * Builds a live option root's chain history from its quotes, the same `book` snapshots a backtest
 * reads: it keeps each contract's latest quote and, when a quote arrives past a boundary of
 * [cadenceMs] (UTC-aligned), hands [write] one snapshot of [root] at that boundary. Every quote in it
 * is aged to the boundary (its `markAgeMs` grows by the time since it arrived); contracts [expiries]
 * does not list (by venue name), or that expire at or before the boundary, are left out, and a
 * boundary with nothing left writes nothing. Quote-driven, so a feed that stops writes nothing more;
 * a gap of several boundaries writes one snapshot, at the first one passed.
 *
 * ```kotlin
 * val recorder = ChainRecorder("DERIBIT:BTC_USDC", 300_000, { catalog.expiries }) { store.append(it.root, it) }
 * gatewayQuotes.forEach(recorder::record)
 * ```
 */
class ChainRecorder(
    private val root: String,
    private val cadenceMs: Long,
    private val expiries: () -> Map<String, Long>,
    private val write: (ChainSnapshot) -> Unit,
) {
    private val latest = HashMap<String, ChainQuote>()
    private var boundary: Long? = null

    init {
        require(cadenceMs > 0) { "ChainRecorder.cadenceMs must be > 0: $cadenceMs" }
    }

    /** Takes [quote] as its contract's latest, unless an earlier-received one is newer. */
    @Synchronized
    fun record(quote: ChainQuote) {
        val next = boundary
        if (next != null && quote.atMs > next) {
            snapshotAt(next)?.let(write)
        }
        if (next == null || quote.atMs > next) boundary = quote.atMs - Math.floorMod(quote.atMs, cadenceMs) + cadenceMs
        val held = latest[quote.contract]
        if (held == null || held.atMs <= quote.atMs) latest[quote.contract] = quote
    }

    private fun snapshotAt(atMs: Long): ChainSnapshot? {
        val listed = expiries()
        latest.keys.removeIf { contract -> (listed[contract] ?: Long.MIN_VALUE) <= atMs }
        if (latest.isEmpty()) return null
        val quotes =
            latest.values
                .sortedBy { it.contract }
                .map { it.copy(atMs = atMs, markAgeMs = it.markAgeMs + (atMs - it.atMs)) }
        return ChainSnapshot(root, atMs, quotes)
    }
}
