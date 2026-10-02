package com.qkt.connector.mt5

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.UnreportedCost
import com.qkt.instrument.VenueInstrumentSpec
import java.math.BigDecimal
import java.time.DayOfWeek

/**
 * The venue's spec for [qktSymbol] as the engine's [InstrumentMeta], read from `/symbol_info`. Swap is
 * taken when the venue quotes it in points (or has it disabled); any other swap mode, or a triple-swap
 * day outside Monday to Friday, is reported unset. Commission is an account-group property the
 * endpoint does not carry, so it is always reported unset.
 */
internal fun MT5Client.instrumentSpec(
    profile: MT5BrokerProfile,
    qktSymbol: String,
): VenueInstrumentSpec? {
    val brokerSymbol = MT5Symbol(profile.symbolPolicy).toBroker(qktSymbol.substringAfter(':'))
    val info = getSymbolInfo(brokerSymbol) ?: return null
    val meta =
        InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = info.contractSize,
            volumeStep = info.volumeStep,
            volumeMin = info.volumeMin,
            volumeMax = info.volumeMax,
            pointSize = info.point,
            digits = info.digits,
            tradeStopsLevelPoints = info.tradeStopsLevel,
        )
    val unreported = mutableMapOf(UnreportedCost.COMMISSION to "the symbol endpoint does not report commission")
    return when (val swap = swapTerms(info.swap)) {
        is SwapTerms.Points ->
            VenueInstrumentSpec(
                meta.copy(swapLongPoints = swap.long, swapShortPoints = swap.short, swapTripleDay = swap.tripleDay),
                unreported,
            )
        is SwapTerms.Unread -> VenueInstrumentSpec(meta, unreported + (UnreportedCost.SWAP to swap.reason))
    }
}

private sealed interface SwapTerms {
    data class Points(
        val long: BigDecimal,
        val short: BigDecimal,
        val tripleDay: DayOfWeek,
    ) : SwapTerms

    data class Unread(
        val reason: String,
    ) : SwapTerms
}

private fun swapTerms(swap: MT5SymbolSwap?): SwapTerms {
    if (swap == null) return SwapTerms.Unread("the gateway reports no swap")
    if (swap.mode == SWAP_DISABLED) return SwapTerms.Points(BigDecimal.ZERO, BigDecimal.ZERO, DayOfWeek.WEDNESDAY)
    if (swap.mode != SWAP_POINTS) return SwapTerms.Unread("swap_mode ${swap.mode} is not points")
    if (swap.tripleDay !in 1..5) {
        return SwapTerms.Unread("swap_rollover3days ${swap.tripleDay} is not Monday to Friday")
    }
    return SwapTerms.Points(swap.long, swap.short, DayOfWeek.of(swap.tripleDay))
}

private const val SWAP_DISABLED = 0
private const val SWAP_POINTS = 1
