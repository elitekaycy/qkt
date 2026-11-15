package com.qkt.connector.gateway

import com.qkt.instrument.EventTerms
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.ProbabilityFee
import com.qkt.instrument.StrikePredicate
import com.qkt.instrument.StrikeType
import com.qkt.instrument.TradeMode
import com.qkt.instrument.VenueInstrumentSpec
import java.math.BigDecimal

/**
 * This listed `event` instrument as the engine's [InstrumentMeta] for [qktSymbol], with its [EventTerms]: one contract
 * pays 1, so `contractSize` is 1 and `pointSize` the listing's tick. Its family root is the symbol's venue prefix
 * and the series code written like a symbol (`-` as `_`): `KALSHI:KXINXU`. An event has no financing and its fee shape
 * is in its terms, so no cost field is reported unset. A listing missing a term qkt needs, or whose terms disagree
 * (bounds that do not fit the strike type, a close before the open), is refused with its code, never guessed.
 */
internal fun WireInstrument.eventSpec(qktSymbol: String): VenueInstrumentSpec {
    try {
        require(BigDecimal(contractSize).compareTo(BigDecimal.ONE) == 0) { "contract_size $contractSize is not 1" }
        val tick = BigDecimal(tickSize)
        val meta =
            InstrumentMeta(
                qktSymbol = qktSymbol,
                contractSize = BigDecimal.ONE,
                volumeStep = BigDecimal(volumeStep),
                volumeMin = BigDecimal(volumeMin),
                volumeMax = null,
                pointSize = tick,
                digits = tick.stripTrailingZeros().scale().coerceAtLeast(0),
                tradeStopsLevelPoints = 0,
                currency = currency,
                category = category,
                tradeMode = trading?.let(::tradeModeOf) ?: TradeMode.FULL,
                derivative = eventTerms(qktSymbol.substringBefore(':')),
            )
        return VenueInstrumentSpec(meta)
    } catch (e: IllegalArgumentException) {
        throw IllegalArgumentException("gateway event instrument '$code' is malformed: ${e.message}", e)
    } catch (e: IllegalStateException) {
        throw IllegalArgumentException("gateway event instrument '$code' is malformed: ${e.message}", e)
    }
}

private fun WireInstrument.eventTerms(venue: String): EventTerms {
    val series = need(series, "series")
    return EventTerms(
        root = "$venue:${series.replace('-', '_')}",
        event = need(event, "event"),
        predicate = StrikePredicate(strikeTypeOf(need(strikeType, "strike_type")), decimal(floor), decimal(cap)),
        openAtMs = need(openAt, "open_at"),
        closeAtMs = need(closeAt, "close_at"),
        expiryMs = need(expiry, "expiry"),
        occursAtMs = occursAt,
        occasion = occasion,
        canCloseEarly = canCloseEarly ?: false,
        exclusive = exclusive ?: false,
        frequency = frequency,
        title = title,
        fee = fee(),
        outcome = decimal(outcome),
    )
}

private fun WireInstrument.fee(): ProbabilityFee? =
    when (feeModel) {
        null -> null
        "probability" ->
            ProbabilityFee(
                takerRate = BigDecimal(need(takerFeeRate, "taker_fee_rate")),
                makerRate = decimal(makerFeeRate),
                rounding = BigDecimal(need(feeRounding, "fee_rounding")),
            )
        else -> throw IllegalArgumentException("fee_model '$feeModel' is not one VGP defines")
    }

private fun strikeTypeOf(wire: String): StrikeType =
    StrikeType.entries.firstOrNull { it.name.lowercase() == wire }
        ?: throw IllegalArgumentException("strike_type '$wire' is not one VGP defines")

private fun decimal(text: String?): BigDecimal? = text?.let(::BigDecimal)

private fun <T : Any> need(
    value: T?,
    field: String,
): T = value ?: throw IllegalArgumentException("$field is missing")
