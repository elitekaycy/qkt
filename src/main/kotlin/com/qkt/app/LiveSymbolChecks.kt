package com.qkt.app

import com.qkt.accounting.AccountingEngine
import com.qkt.accounting.requireBookable

/**
 * The start-up checks a live or paper session runs on its traded [symbols]: every symbol must book in
 * (or convert to) the account currency, and continuous futures streams (`VENUE:ROOT@front`) are
 * refused until live contract rolling exists — they are backtest-only for now.
 */
internal fun requireLiveTradable(
    symbols: Collection<String>,
    accounting: AccountingEngine,
) {
    val continuous = symbols.filter { '@' in it }
    require(continuous.isEmpty()) {
        "continuous futures streams $continuous can only be backtested for now; trade a listed contract instead"
    }
    accounting.requireBookable(symbols)
}
