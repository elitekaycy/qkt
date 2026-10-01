package com.qkt.marketdata.store.deribit

import com.qkt.instrument.OptionRoot
import java.math.BigDecimal

/**
 * Fails when an instrument the venue lists for [root] disagrees with what `instruments.yaml` declares
 * for it — contract size, tick grid, minimum trade, settlement index — so a misdeclared root cannot
 * scale every P&L silently. Fields the venue leaves out (the history host omits some) are not judged.
 */
internal fun requireMatchesRoot(
    instrument: DeribitInstrument,
    root: OptionRoot,
) {
    fun decimal(value: kotlinx.serialization.json.JsonPrimitive?) = value?.content?.toBigDecimalOrNull()

    fun check(
        field: String,
        declared: Any,
        listed: Any?,
        same: Boolean,
    ) = check(listed == null || same) {
        "${root.root} declares $field $declared but Deribit lists $listed for ${instrument.name}; fix instruments.yaml"
    }
    val size = decimal(instrument.contractSize)
    check(
        "contractSize",
        root.contractSize.toPlainString(),
        size?.toPlainString(),
        size?.compareTo(root.contractSize) == 0,
    )
    val tick = decimal(instrument.tickSize)
    check(
        "tickSize",
        root.tickSteps.base.toPlainString(),
        tick?.toPlainString(),
        tick?.compareTo(root.tickSteps.base) == 0,
    )
    val minimum = decimal(instrument.minTradeAmount)
    check(
        "volumeMin",
        root.volumeMin.toPlainString(),
        minimum?.toPlainString(),
        minimum?.compareTo(root.volumeMin) == 0,
    )
    check("underlyingIndex", root.underlyingIndex, instrument.priceIndex, instrument.priceIndex == root.underlyingIndex)
    val steps = instrument.tickSizeSteps?.map { BigDecimal(it.above.content) to BigDecimal(it.tick.content) }
    val declaredSteps = root.tickSteps.steps.map { it.above to it.tick }
    val sameSteps =
        steps != null &&
            steps.size == declaredSteps.size &&
            steps.zip(declaredSteps).all { (a, b) ->
                a.first.compareTo(b.first) == 0 &&
                    a.second.compareTo(b.second) == 0
            }
    check("tickSteps", declaredSteps, steps, sameSteps)
}
