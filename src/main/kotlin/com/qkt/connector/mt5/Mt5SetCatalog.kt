package com.qkt.connector.mt5

import com.qkt.connectivity.AccountConfig
import com.qkt.instrument.universe.VenueCatalog

/**
 * The venue's symbols as the catalog a live filtered symbol set ranks: every name `/symbols` lists that this account
 * trades (its suffix and aliases map it back to itself), each as its `/symbol_info` meta, the same meta
 * `qkt instruments pull` writes for a backtest. One `/symbol_info` read per listed symbol, once at session start; a
 * symbol the gateway reports no info for is left out. A gateway that cannot list is refused, naming the account.
 */
internal fun MT5Client.instrumentCatalog(
    profile: MT5BrokerProfile,
    config: AccountConfig,
): VenueCatalog {
    val names =
        listSymbols()
            ?: error(
                "brokers.${config.name}: the mt5 gateway did not list its symbols (GET /symbols): " +
                    "${lastReadFailure()}",
            )
    val symbols = MT5Symbol(profile.symbolPolicy)
    val rows =
        names
            .filter { symbols.toBroker(symbols.toQkt(it)) == it }
            .mapNotNull { name -> getSymbolInfo(name)?.instrumentMeta(config.symbolPrefix + symbols.toQkt(name)) }
    return VenueCatalog(rows)
}
