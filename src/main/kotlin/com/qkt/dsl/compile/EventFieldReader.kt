package com.qkt.dsl.compile

import com.qkt.dsl.DslVocabulary
import com.qkt.dsl.ast.StrategyAst

/** One event-contract field a strategy reads: [symbol]'s [field] (`minutes_left`, `taker_fee`, ...). */
data class EventRead(
    val symbol: String,
    val field: String,
)

/**
 * A compiled strategy that reads event-contract fields (`<alias>.minutes_left`, `.taker_fee`, ...). The runtime
 * verifies each read stream is an event contract, and that one read for its fee has a known fee model, before it
 * goes live ([com.qkt.app.requireEventTerms]).
 */
interface EventFieldReader {
    /** The streams and fields the strategy's event fields read. */
    val eventReads: Set<EventRead>
}

/** The event fields [ast] reads anywhere (rules, actions, lets, sequences, schedules), by resolved symbol. */
internal fun eventReads(
    ast: StrategyAst,
    streams: Map<String, HubKey>,
): Set<EventRead> =
    collectMetaRefs(ast, streams, DslVocabulary.eventFields).map { EventRead(it.qktSymbol, it.field) }.toSet()
