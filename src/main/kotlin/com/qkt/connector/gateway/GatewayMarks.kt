package com.qkt.connector.gateway

import com.qkt.marketdata.marks.MarkPrices
import com.qkt.marketdata.marks.MarkSample
import java.util.concurrent.ConcurrentHashMap

/**
 * A gateway account's live marks: the newest quote of each venue code that carried a mark or an index
 * ([heard], kept by reference, so a quote costs no allocation here), read back by qkt symbol through the
 * subscription's listing ([listed]). Served only when the gateway declares `mark_prices` ([capabilities]);
 * otherwise [problem] says so, and a strategy reading marks does not start.
 */
internal class GatewayMarks(
    prefix: String,
    private val capabilities: () -> Collection<String>,
) : MarkPrices {
    private val symbols = GatewaySymbols(prefix)
    private val latest = ConcurrentHashMap<String, WireQuote>()

    /** Takes the codes of the subscription's [listing]. */
    fun listed(listing: List<WireInstrument>) = symbols.update(listing.map { it.code })

    /** Keeps [quote] as its code's newest when it carries a mark or an index. */
    fun heard(quote: WireQuote) {
        if (quote.mark != null || quote.index != null) latest[quote.symbol] = quote
    }

    /** The newest quoted mark and index of [symbol], whatever [atMs]: live, every value heard is known. */
    override fun at(
        symbol: String,
        windowMs: Long,
        atMs: Long,
    ): MarkSample? {
        val quote = symbols.venue(symbol)?.let(latest::get) ?: return null
        return MarkSample(quote.time, quote.mark?.toBigDecimalOrNull(), quote.index?.toBigDecimalOrNull())
    }

    override fun problem(symbol: String): String? =
        if (MARK_PRICES in capabilities()) {
            null
        } else {
            "its gateway does not serve mark prices (capability '$MARK_PRICES'); upgrade the gateway or its adapter"
        }

    private companion object {
        const val MARK_PRICES = "mark_prices"
    }
}
