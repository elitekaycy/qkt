package com.qkt.dsl.compile

import com.qkt.strategy.StrategyContext

/** Fails binding when a `<stream>.<meta field>` read has no InstrumentMeta registered for its symbol. */
internal fun validateMetaRefs(
    metaRefs: List<MetaRef>,
    ctx: StrategyContext,
) {
    val registry = ctx.instruments
    val missing = metaRefs.firstOrNull { registry.lookup(it.qktSymbol) == null }
    if (missing != null) {
        error(
            "Strategy '${ctx.strategyId}' references '${missing.stream}.${missing.field}' " +
                "but no InstrumentMeta is registered for ${missing.qktSymbol}. " +
                "Populate it via the MT5 broker connection (live) or a YAML manifest " +
                "in qkt.config.yaml (backtest).",
        )
    }
}
