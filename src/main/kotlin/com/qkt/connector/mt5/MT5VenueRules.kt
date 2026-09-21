package com.qkt.connector.mt5

import java.math.BigDecimal

/**
 * The venue's limits for one symbol, as placement needs them. Operator overrides from the
 * profile win over what `/symbol_info` reports, e.g. a pinned 0.01 step is used without a
 * gateway round-trip; overrides carry no freeze level, so it reads 0 there.
 */
internal data class MT5VenueRules(
    val volumeStep: BigDecimal,
    val volumeMin: BigDecimal,
    val volumeMax: BigDecimal?,
    val digits: Int,
    val pointSize: BigDecimal,
    val tradeStopsLevelPoints: Int,
    val tradeFreezeLevelPoints: Int,
) {
    companion object {
        fun of(spec: InstrumentSpec): MT5VenueRules =
            MT5VenueRules(
                volumeStep = spec.volumeStep,
                volumeMin = spec.minVolume,
                volumeMax = spec.maxVolume,
                digits = spec.digits,
                pointSize = spec.pointSize,
                tradeStopsLevelPoints = spec.tradeStopsLevelPoints,
                tradeFreezeLevelPoints = 0,
            )

        fun of(info: MT5SymbolInfo): MT5VenueRules =
            MT5VenueRules(
                volumeStep = info.volumeStep,
                volumeMin = info.volumeMin,
                volumeMax = info.volumeMax,
                digits = info.digits,
                pointSize = info.point,
                tradeStopsLevelPoints = info.tradeStopsLevel,
                tradeFreezeLevelPoints = info.tradeFreezeLevel,
            )
    }
}
