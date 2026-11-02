package com.qkt.instrument

/** [inner] with [source] as its corporate actions: a backtest reads its data root's whatever instruments file it has. */
class WithCorporateActions(
    private val inner: InstrumentRegistry,
    private val source: CorporateActionSource,
) : InstrumentRegistry by inner {
    override fun corporateActions(): CorporateActionSource = source
}
