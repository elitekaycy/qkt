package com.qkt.dsl.compile

import com.qkt.derivatives.options.chain.ChainView
import com.qkt.instrument.InstrumentRegistry

/**
 * What compiling a strategy's option structures shares: the [aliases] its `OPEN … = OPTIONS ON …`
 * actions open (so `POSITION.<alias>` and `CLOSE <alias>` know a structure from a stream), and one
 * [ChainView] per instrument registry, read by the leg selector and the Greeks alike.
 */
class StructureSupport(
    val aliases: Set<String> = emptySet(),
) {
    private var views: Pair<InstrumentRegistry, ChainView>? = null

    /** The chain view over [instruments]' option roots, built once. */
    fun view(instruments: InstrumentRegistry): ChainView =
        views?.takeIf { it.first === instruments }?.second ?: ChainView(instruments).also { views = instruments to it }
}
