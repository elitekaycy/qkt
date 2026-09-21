package com.qkt.connector.mt5

import com.qkt.instrument.InstrumentMeta

/**
 * The venue's spec for [qktSymbol] as the engine's [InstrumentMeta], read from `/symbol_info`.
 * Commission and swaps are account-group properties the endpoint does not carry, so they keep
 * their defaults and must be set deliberately where a backtest needs them.
 */
internal fun MT5Client.instrumentSpec(
    profile: MT5BrokerProfile,
    qktSymbol: String,
): InstrumentMeta? {
    val brokerSymbol = MT5Symbol(profile.symbolPolicy).toBroker(qktSymbol.substringAfter(':'))
    val info = getSymbolInfo(brokerSymbol) ?: return null
    return InstrumentMeta(
        qktSymbol = qktSymbol,
        contractSize = info.contractSize,
        volumeStep = info.volumeStep,
        volumeMin = info.volumeMin,
        volumeMax = info.volumeMax,
        pointSize = info.point,
        digits = info.digits,
        tradeStopsLevelPoints = info.tradeStopsLevel,
    )
}
