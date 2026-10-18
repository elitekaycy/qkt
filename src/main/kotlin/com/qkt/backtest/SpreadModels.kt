package com.qkt.backtest

import com.qkt.instrument.InstrumentRegistry

/** The spread model each of [symbols] states, as `SYMBOL fixed N points` or `SYMBOL min N points`. */
internal fun spreadModels(
    instruments: InstrumentRegistry,
    symbols: List<String>,
    brokerKind: BrokerKind,
): List<String> {
    if (brokerKind != BrokerKind.MT5_SIM) return emptyList()
    return symbols.distinct().sorted().mapNotNull { symbol ->
        val meta = instruments.lookup(symbol) ?: return@mapNotNull null
        meta.spreadPoints?.let { "$symbol fixed $it points" }
            ?: meta.minSpreadPoints?.let { "$symbol min $it points" }
    }
}
