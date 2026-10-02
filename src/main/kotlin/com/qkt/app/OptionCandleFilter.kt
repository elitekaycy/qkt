package com.qkt.app

import com.qkt.dsl.compile.DslCompiledStrategy
import com.qkt.instrument.InstrumentRegistry
import com.qkt.strategy.Strategy

/**
 * Which symbols form candles when a run feeds whole option roots: an option contract only when a
 * strategy declares it, every other symbol always. A fed root quotes every contract it holds, and
 * contracts no strategy reads must move prices and fills without becoming bars and analytics
 * subjects. Null (no filter) for runs without option roots.
 */
internal fun optionCandleFilter(
    strategies: List<Pair<String, Strategy>>,
    instruments: InstrumentRegistry,
): ((String) -> Boolean)? {
    val options = instruments.options() ?: return null
    val declared =
        strategies
            .flatMap {
                (it.second as? DslCompiledStrategy)?.declaredStreams?.values.orEmpty().map { k ->
                    k.qktSymbol
                }
            }.toSet()
    return { symbol -> symbol in declared || options.optionRoot(symbol) == null }
}

/** Fails when [strategies] repeat an id or carry a blank one. */
internal fun requireValidStrategyIds(strategies: List<Pair<String, Strategy>>) {
    require(
        strategies
            .map {
                it.first
            }.toSet()
            .size == strategies.size,
    ) { "Strategy IDs must be unique: ${strategies.map { it.first }}" }
    require(strategies.all { it.first.isNotBlank() }) { "Strategy ID must be non-blank" }
}
