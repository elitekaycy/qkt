package com.qkt.instrument

import java.math.BigDecimal

/**
 * The catalog entry of the dated futures contract [qktSymbol] (`BINANCE_UM:BTCUSDT_240927`), or
 * null when [qktSymbol] is not a dated contract of a declared root or its root has no catalog.
 */
fun InstrumentRegistry.listedContract(qktSymbol: String): ListedContract? {
    val terms = lookup(qktSymbol)?.derivative as? FutureTerms ?: return null
    if (terms.expiryMs == null) return null
    val name = qktSymbol.substringAfter(':')
    return futures()?.catalog(terms.root)?.contracts?.firstOrNull { it.symbol == name }
}

/** The exchange's delivery price of the dated contract [qktSymbol], when its catalog records one. */
fun InstrumentRegistry.deliveryPrice(qktSymbol: String): BigDecimal? =
    listedContract(qktSymbol)?.deliveryPrice?.let(::BigDecimal)

/**
 * The futures among [symbols]: continuous streams of a declared root, listed contracts and a root's
 * perpetual. These always fill on the exchange simulator in a backtest, whatever the broker.
 */
fun InstrumentRegistry.futuresSymbols(symbols: Collection<String>): Set<String> {
    val directory = futures() ?: return emptySet()
    return symbols
        .filter { symbol ->
            val terms = lookup(symbol)?.derivative as? FutureTerms
            directory.rootOfContinuous(symbol) != null || terms?.expiryMs != null || terms?.perpetual == true
        }.toSet()
}
