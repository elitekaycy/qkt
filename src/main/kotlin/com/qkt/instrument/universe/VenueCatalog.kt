package com.qkt.instrument.universe

import com.qkt.instrument.InstrumentMeta

/**
 * One venue's instruments as a filtered symbol set reads them: [rows], every instrument the catalog can describe,
 * and [omitted], what the catalog lists but cannot describe (null when nothing), which a refusal states so an empty
 * set is never a silent mystery. The same [UniverseResolver] ranks these rows in a backtest and live.
 */
data class VenueCatalog(
    val rows: List<InstrumentMeta>,
    val omitted: String? = null,
)
