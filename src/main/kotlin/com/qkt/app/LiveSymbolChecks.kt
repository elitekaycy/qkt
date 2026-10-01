package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.requireBookable
import com.qkt.derivatives.options.chain.ChainAnalyticsSymbol
import com.qkt.derivatives.options.chain.OptionRootSymbol

/**
 * The start-up checks a live or paper session runs on its traded [symbols]: every symbol must book in
 * (or convert to) the account currency; continuous futures streams (`VENUE:ROOT@front`) are refused
 * until live contract rolling exists — they are backtest-only for now; and a chain analytics stream
 * needs its option root fed (`OPTIONS:<VENUE>.<ROOT>`), because live chains are recorded from the
 * quotes of a fed root.
 */
internal fun requireLiveTradable(
    symbols: Collection<String>,
    accounting: AccountingEngine,
) {
    val continuous = symbols.filter { '@' in it }
    require(continuous.isEmpty()) {
        "continuous futures streams $continuous can only be backtested for now; trade a listed contract instead"
    }
    val fed = symbols.mapNotNull { OptionRootSymbol.parse(it).getOrNull()?.root }.toSet()
    val unfed = symbols.mapNotNull { ChainAnalyticsSymbol.parse(it).getOrNull()?.root }.filterNot { it in fed }
    require(unfed.isEmpty()) {
        "live chain streams need their option root fed so its chain is recorded: declare " +
            unfed.distinct().joinToString { OptionRootSymbol.PREFIX + it.replace(':', '.') }
    }
    accounting.requireBookable(symbols)
}
