package com.qkt.instrument

import java.math.BigDecimal

/**
 * Scales overnight financing for every instrument in [inner], leaving all other metadata
 * untouched. Backs `--swap-scale`: run-level financing stress without editing `instruments.yaml`
 * (`0` prices swap-free, `2` doubles it). Rollover hour and triple day stay from metadata —
 * timing is not a costing question. Composes with [CommissionOverrideRegistry]; delegation
 * preserves everything else.
 */
class SwapScaleRegistry(
    private val inner: InstrumentRegistry,
    private val factor: BigDecimal,
) : InstrumentRegistry by inner {
    override fun lookup(qktSymbol: String): InstrumentMeta? =
        inner.lookup(qktSymbol)?.let {
            it.copy(
                swapLongPoints = it.swapLongPoints.multiply(factor),
                swapShortPoints = it.swapShortPoints.multiply(factor),
            )
        }
}
