package com.qkt.cli

import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.UnreportedCost
import com.qkt.instrument.VenueInstrumentSpec

/**
 * One `instruments:` entry as `qkt instruments pull` writes it. Every cost field is written, zeros
 * included, so the file states what was known; a cost the venue did not report is written as a
 * commented note naming each field and why, so it loads as unset rather than as the venue's zero.
 */
internal fun VenueInstrumentSpec.entryText(): String =
    buildString {
        val e = meta

        fun cost(
            cost: UnreportedCost,
            vararg values: String,
        ) {
            val reason = unreported[cost]
            cost.keys.zip(values).forEach { (key, value) ->
                if (reason == null) {
                    appendLine("    $key: $value")
                } else {
                    appendLine("    # $key: not reported by the venue ($reason); set it by hand")
                }
            }
        }
        appendLine("  - qktSymbol: ${e.qktSymbol}")
        appendLine("    contractSize: ${e.contractSize.toPlainString()}")
        appendLine("    volumeStep: ${e.volumeStep.toPlainString()}")
        appendLine("    volumeMin: ${e.volumeMin.toPlainString()}")
        e.volumeMax?.let { appendLine("    volumeMax: ${it.toPlainString()}") }
        appendLine("    pointSize: ${e.pointSize.toPlainString()}")
        appendLine("    digits: ${e.digits}")
        appendLine("    tradeStopsLevelPoints: ${e.tradeStopsLevelPoints}")
        cost(UnreportedCost.COMMISSION, e.commissionPerLot.toPlainString())
        appendLine("    slippagePoints: ${e.slippagePoints}")
        cost(
            UnreportedCost.SWAP,
            e.swapLongPoints.toPlainString(),
            e.swapShortPoints.toPlainString(),
            e.swapTripleDay.toString(),
        )
        appendLine("    swapRolloverHourUtc: ${e.swapRolloverHourUtc}")
        e.currency?.let { appendLine("    currency: $it") }
    }

/**
 * This pull with what [blocks] already sets by hand for [existing]'s symbol kept: an unreported cost
 * the entry sets any field of, its slippage, and its currency when the venue states none. A first pull keeps
 * nothing.
 */
internal fun VenueInstrumentSpec.keeping(
    existing: InstrumentMeta?,
    blocks: InstrumentEntryBlocks,
): VenueInstrumentSpec {
    existing ?: return this
    val symbol = meta.qktSymbol
    val kept = unreported.keys.filter { cost -> cost.keys.any { blocks.declares(symbol, it) } }.toSet()
    var m = meta.copy(slippagePoints = existing.slippagePoints, currency = meta.currency ?: existing.currency)
    if (UnreportedCost.COMMISSION in kept) m = m.copy(commissionPerLot = existing.commissionPerLot)
    if (UnreportedCost.SWAP in kept) {
        m =
            m.copy(
                swapLongPoints = existing.swapLongPoints,
                swapShortPoints = existing.swapShortPoints,
                swapTripleDay = existing.swapTripleDay,
            )
    }
    return VenueInstrumentSpec(m, unreported - kept)
}
