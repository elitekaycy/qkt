package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.requireBookable
import com.qkt.derivatives.futures.ContinuousChains
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.OptionRootSymbol

/**
 * The start-up checks a live or paper session runs on its traded [symbols]: every symbol must book in
 * (or convert to) the account currency; a continuous futures stream (`VENUE:ROOT@front`) must be one of
 * the session's live streams ([continuous]: a declared root with its roll history on disk); and a chain
 * analytics stream needs its option root fed (`OPTIONS:<VENUE>.<ROOT>`), because live chains are recorded
 * from the quotes of a fed root.
 */
internal fun requireLiveTradable(
    symbols: Collection<String>,
    accounting: AccountingEngine,
    continuous: ContinuousChains? = null,
) {
    val unserved = symbols.filter { '@' in it && continuous?.isContinuous(it) != true }
    require(unserved.isEmpty()) {
        "continuous futures streams $unserved need a declared futures root with its roll history (qkt fetch <ROOT> --rolls)"
    }
    val fed = symbols.mapNotNull { OptionRootSymbol.parse(it).getOrNull()?.root }.toSet()
    val unfed = symbols.mapNotNull { ChainAnalyticsSymbol.parse(it).getOrNull()?.root }.filterNot { it in fed }
    require(unfed.isEmpty()) {
        "live chain streams need their option root fed so its chain is recorded: declare " +
            unfed.distinct().joinToString { OptionRootSymbol.PREFIX + it.replace(':', '.') }
    }
    accounting.requireBookable(symbols)
}
