package com.qkt.connector.mt5

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.TradeMode
import com.qkt.instrument.withInstrumentCategories

/**
 * This symbol's `/symbol_info` as the engine's [InstrumentMeta] for [qktSymbol]: the one place MT5's fields become
 * qkt's, read by the live instrument registry and by `qkt instruments pull` alike. Swap and commission are added by
 * [instrumentSpec], which states what the venue leaves unreported.
 */
internal fun MT5SymbolInfo.instrumentMeta(qktSymbol: String): InstrumentMeta =
    InstrumentMeta(
        qktSymbol = qktSymbol,
        contractSize = contractSize,
        volumeStep = volumeStep,
        volumeMin = volumeMin,
        volumeMax = volumeMax,
        pointSize = point,
        digits = digits,
        tradeStopsLevelPoints = tradeStopsLevel,
        tickSize = tickSize,
        category = category,
        tradeMode = tradeMode?.let(::mt5TradeMode) ?: TradeMode.FULL,
    )

/** MT5's `SYMBOL_TRADE_MODE` [code] as a [TradeMode]; an undefined code is reported as fully tradable, as MT5 treats it. */
internal fun mt5TradeMode(code: Int): TradeMode =
    when (code) {
        0 -> TradeMode.DISABLED
        1 -> TradeMode.LONG_ONLY
        2 -> TradeMode.SHORT_ONLY
        3 -> TradeMode.CLOSE_ONLY
        else -> TradeMode.FULL
    }

/** This profile with its calendars' `category:` rules answered from [client]'s instrument specs. */
internal fun MT5BrokerProfile.withInstrumentCategories(
    client: MT5Client = MT5Client(gatewayUrl, serverTimeZone, httpTimeoutMs, retryAttempts, apiKey),
): MT5BrokerProfile =
    copy(symbolCalendars = symbolCalendars.withInstrumentCategories(name) { client.instrumentSpec(this, it)?.meta })
