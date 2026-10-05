package com.qkt.connector.gateway

import com.qkt.derivatives.options.chain.ChainQuote
import com.qkt.derivatives.options.chain.OptionMarks
import java.util.concurrent.ConcurrentHashMap

/**
 * A gateway account's live option marks: the newest quote of each venue code that carried a mark IV
 * ([heard], kept by reference, so a quote costs no allocation here), read back by qkt symbol through the
 * subscription's listing ([listed]) as a chain quote ([gatewayChainQuote]: mark IV, forward, its own time).
 * Served only when the gateway declares `option_marks` ([capabilities]); otherwise [problem] says so, and a
 * strategy reading an option's IV or Greeks does not start.
 */
internal class GatewayOptionMarks(
    prefix: String,
    private val capabilities: () -> Collection<String>,
) : OptionMarks {
    private val symbols = GatewaySymbols(prefix)
    private val latest = ConcurrentHashMap<String, WireQuote>()

    /** Takes the codes of the subscription's [listing]. */
    fun listed(listing: List<WireInstrument>) = symbols.update(listing.map { it.code })

    /** Keeps [quote] as its code's newest when it carries a mark IV. */
    fun heard(quote: WireQuote) {
        if (quote.markIv != null) latest[quote.symbol] = quote
    }

    /** The newest quote heard of [qktSymbol], whatever [atMs]: live, every quote heard is known. */
    override fun at(
        qktSymbol: String,
        atMs: Long,
    ): ChainQuote? = symbols.venue(qktSymbol)?.let(latest::get)?.let(::gatewayChainQuote)

    override fun problem(qktSymbol: String): String? =
        if (OPTION_MARKS in capabilities()) {
            null
        } else {
            "its gateway does not serve option marks (capability '$OPTION_MARKS'); upgrade the gateway or its adapter"
        }

    private companion object {
        const val OPTION_MARKS = "option_marks"
    }
}
