package com.qkt.backtest

import com.qkt.instrument.InstrumentRegistry
import com.qkt.instrument.futuresSymbols
import com.qkt.instrument.optionSymbols

/**
 * The [symbols] a replay fills on the MT5 simulator: none unless [brokerKind] is [BrokerKind.MT5_SIM], and
 * never a futures, perpetual or option symbol, which fill on the exchange and option simulators whatever
 * the broker kind (see `replayBroker`).
 */
internal fun mt5SimSymbols(
    brokerKind: BrokerKind,
    instruments: InstrumentRegistry,
    symbols: Collection<String>,
): Set<String> {
    if (brokerKind != BrokerKind.MT5_SIM) return emptySet()
    val exchange = instruments.futuresSymbols(symbols) + instruments.optionSymbols(symbols)
    return symbols.filterNotTo(LinkedHashSet()) { it in exchange }
}
