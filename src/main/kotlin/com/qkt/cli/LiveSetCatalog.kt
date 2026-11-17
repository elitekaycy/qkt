package com.qkt.cli

import com.qkt.connectivity.TradingAccount
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.instrument.universe.CatalogField
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.instrument.universe.wordRequiredOf

/**
 * A live session's [SetCatalog] (design §3.9): a set over `VENUE:*` reads the listing of the account
 * named `venue` ([TradingAccount.instrumentCatalog]) once, at session start. The listing is the venue's today, so
 * there is no survivorship question. The `kind` the filter pins is passed on, so a venue that can filter its listing
 * reads only that kind.
 */
internal class LiveSetCatalog(
    private val account: (String) -> TradingAccount?,
) : SetCatalog {
    override val source: String = "the venue listing"
    override val survivorsOnly: Boolean = false

    override fun venue(
        alias: String,
        spec: SymbolSetSpec.Filtered,
    ): VenueCatalog {
        val venue =
            account(spec.venue)
                ?: throw BacktestContext.Companion.SetupError(
                    "symbol set '$alias' filters ${spec.venue}:*, and no configured account is named " +
                        spec.venue.lowercase(),
                )
        return venue.instrumentCatalog(spec.filter.wordRequiredOf(CatalogField.KIND))
            ?: throw BacktestContext.Companion.SetupError(
                "symbol set '$alias': brokers.${venue.config.name} (type ${venue.config.type}) cannot list its " +
                    "instruments, so it cannot resolve a filtered set; list the members instead (${spec.venue}:{A, B})",
            )
    }
}
