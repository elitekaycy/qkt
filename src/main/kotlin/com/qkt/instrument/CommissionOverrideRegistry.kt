package com.qkt.instrument

import java.math.BigDecimal

/**
 * Overrides the per-lot commission rate for every instrument in [inner], leaving all other
 * metadata untouched. Backs `--commission-per-lot`: a run-level costing question answered without
 * editing `instruments.yaml`. Delegation preserves futures catalogs, option directories, and
 * missing-reason behavior; only [InstrumentMeta.commissionPerLot] is replaced.
 */
class CommissionOverrideRegistry(
    private val inner: InstrumentRegistry,
    private val rate: BigDecimal,
) : InstrumentRegistry by inner {
    override fun lookup(qktSymbol: String): InstrumentMeta? =
        inner.lookup(qktSymbol)?.copy(commissionPerLot = rate)
}
