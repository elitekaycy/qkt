package com.qkt.derivatives.options.chain

import com.qkt.instrument.OptionCatalog
import java.util.TreeMap

/** The snapshots built from a trade feed and the traded contracts the catalog does not list. */
data class TradeChain(
    val snapshots: List<ChainSnapshot>,
    val unknownContracts: Set<String>,
)

/**
 * Folds an option trade feed into chain snapshots at fixed instants, for markets whose only free
 * history is trades. At each instant `t` a catalogued contract is quoted once it has traded at or
 * before `t` and until its expiry (absent at the expiry instant itself), carrying its last trade's
 * mark, IV and index, the trade's age as `markAgeMs`, and no book. Nothing after `t` is used, and
 * an instant with no contract quoted has no snapshot. Trades are deduplicated by id and ordered by
 * time then sequence, so a feed delivered out of order or with page overlaps builds the same chain.
 */
class TradeChainBuilder(
    private val catalog: OptionCatalog,
) {
    private val expiries = catalog.contracts.associate { it.symbol to it.expiryMs }

    /**
     * Snapshots at `fromMs, fromMs + everyMs, …` before `toMs`. Pass trades from before `fromMs` too:
     * a contract last traded then is quoted from the first instant, aged accordingly.
     */
    fun build(
        trades: List<OptionTrade>,
        fromMs: Long,
        toMs: Long,
        everyMs: Long,
    ): TradeChain {
        require(fromMs < toMs) { "chain window is empty: from $fromMs is not before to $toMs" }
        require(everyMs > 0) { "chain interval must be > 0 ms: $everyMs" }
        val (known, unknown) = trades.distinctBy { it.tradeId }.partition { it.contract in expiries }
        val feed = known.sortedWith(compareBy({ it.timestampMs }, { it.tradeSeq }))
        val last = TreeMap<String, OptionTrade>()
        val snapshots = mutableListOf<ChainSnapshot>()
        var next = 0
        var at = fromMs
        while (at < toMs) {
            while (next < feed.size && feed[next].timestampMs <= at) {
                last[feed[next].contract] = feed[next]
                next++
            }
            last.entries.removeIf { expiries.getValue(it.key) <= at }
            if (last.isNotEmpty()) snapshots += ChainSnapshot(catalog.root, at, last.values.map { quoteOf(it, at) })
            at += everyMs
        }
        return TradeChain(snapshots, unknown.mapTo(sortedSetOf()) { it.contract })
    }

    private fun quoteOf(
        trade: OptionTrade,
        atMs: Long,
    ) = ChainQuote(
        atMs = atMs,
        contract = trade.contract,
        bid = null,
        ask = null,
        mark = trade.markPrice,
        markIv = trade.iv,
        underlying = trade.indexPrice,
        rate = null,
        markAgeMs = atMs - trade.timestampMs,
        source = QuoteSource.TRADE,
    )
}
