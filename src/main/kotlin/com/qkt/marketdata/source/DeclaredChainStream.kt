package com.qkt.marketdata.source

import com.qkt.common.Money
import com.qkt.derivatives.options.chain.ChainAnalytics
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.ChainSnapshot
import com.qkt.instrument.OptionListing
import com.qkt.instrument.OptionRoot
import com.qkt.instrument.QuoteSource
import java.math.BigDecimal
import java.nio.file.Path

/** A chain analytics [stream] on its declared [root]: the [series] and [dataRoot] it reads, the [listings] it values. */
internal class DeclaredChainStream(
    val stream: ChainAnalyticsSymbol,
    val root: OptionRoot,
    val series: QuoteSource,
    val dataRoot: Path,
    private val listings: Map<String, OptionListing>,
) {
    /** The stream's value at [snapshot], or null where the metric is not defined. */
    fun value(snapshot: ChainSnapshot): BigDecimal? =
        ChainAnalytics
            .value(stream.metric, stream.tenorDays, snapshot, listings, root.maxQuoteAgeMinutes * MS_PER_MINUTE)
            ?.let { BigDecimal.valueOf(it).setScale(Money.SCALE, Money.ROUNDING) }

    private companion object {
        const val MS_PER_MINUTE = 60_000L
    }
}
