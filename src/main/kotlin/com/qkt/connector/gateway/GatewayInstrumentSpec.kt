package com.qkt.connector.gateway

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.TradeMode
import com.qkt.instrument.UnreportedCost
import com.qkt.instrument.VenueInstrumentSpec
import java.math.BigDecimal

/**
 * This listed instrument as the engine's [InstrumentMeta] for [qktSymbol]: the one place a VGP instrument's fields
 * become qkt's. Only a `spot` or `equity` instrument is described this way; a future or option needs exchange terms
 * (expiry, margin, fees) its listing does not carry, so it is left to its contract catalog (null). Commission and
 * swap are not on the listing and are reported unset.
 */
internal fun WireInstrument.instrumentSpec(qktSymbol: String): VenueInstrumentSpec? {
    if (kind != "spot" && kind != "equity") return null
    val tick = BigDecimal(tickSize)
    val meta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = BigDecimal(contractSize),
            volumeStep = BigDecimal(volumeStep),
            volumeMin = BigDecimal(volumeMin),
            volumeMax = null,
            pointSize = tick,
            digits = tick.stripTrailingZeros().scale().coerceAtLeast(0),
            tradeStopsLevelPoints = 0,
            currency = currency,
            category = category,
            tradeMode = trading?.let(::tradeModeOf) ?: TradeMode.FULL,
        )
    val unreported =
        mapOf(
            UnreportedCost.COMMISSION to "the gateway listing does not report commission",
            UnreportedCost.SWAP to "the gateway listing does not report financing",
        )
    return VenueInstrumentSpec(meta, unreported)
}

/** A VGP `trading` value as a [TradeMode]; one qkt does not know is refused by name rather than read as tradable. */
internal fun tradeModeOf(wire: String): TradeMode =
    when (wire) {
        "long_only" -> TradeMode.LONG_ONLY
        "short_only" -> TradeMode.SHORT_ONLY
        "close_only" -> TradeMode.CLOSE_ONLY
        "disabled" -> TradeMode.DISABLED
        else -> error("gateway instrument trading '$wire' is not one VGP defines")
    }
