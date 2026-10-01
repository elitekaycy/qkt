package com.qkt.marketdata.store.deribit

import com.qkt.derivatives.options.chain.OptionTrade
import com.qkt.instrument.OptionRoot
import java.math.BigDecimal
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Every option trade of a root in a time window, from Deribit's history host. The venue lists trades
 * of all options in the root's currency; pages are requested oldest first, each starting at the
 * previous page's last millisecond (bounds are inclusive) so none is lost, then deduplicated by
 * trade id and narrowed to the root's contracts (`BTC_USDC-…` for `DERIBIT:BTC_USDC`).
 */
class DeribitTradeHistory(
    private val client: DeribitClient,
) {
    /** [root]'s option trades with `fromMs <= timestamp < toMs`, oldest first. */
    fun trades(
        root: OptionRoot,
        fromMs: Long,
        toMs: Long,
    ): List<OptionTrade> {
        require(fromMs < toMs) { "trade window is empty: from $fromMs is not before to $toMs" }
        val prefix = root.root.substringAfter(':') + "-"
        val seen = HashSet<String>()
        val trades = mutableListOf<OptionTrade>()
        var start = fromMs
        while (true) {
            val page = client.optionTrades(root.currency, start, toMs - 1)
            for (trade in page.trades) {
                if (trade.name.startsWith(prefix) && trade.timestamp in fromMs until toMs && seen.add(trade.id)) {
                    trades += trade.toOptionTrade()
                }
            }
            if (!page.hasMore) return trades
            val last = page.trades.lastOrNull()?.timestamp ?: start
            check(last > start) {
                "Deribit lists more than ${client.pageSize} ${root.currency} option trades at $start ms; " +
                    "raise the page size to page past it"
            }
            start = last
        }
    }

    private fun DeribitTrade.toOptionTrade() =
        OptionTrade(
            tradeId = id,
            timestampMs = timestamp,
            tradeSeq = seq,
            contract = name,
            markPrice = required(mark, "mark_price"),
            iv = iv?.contentOrNull?.let(::BigDecimal),
            indexPrice = required(index, "index_price"),
        )

    private fun DeribitTrade.required(
        value: JsonPrimitive,
        field: String,
    ): BigDecimal = BigDecimal(requireNotNull(value.contentOrNull) { "Deribit trade $id ($name) has no $field" })
}
