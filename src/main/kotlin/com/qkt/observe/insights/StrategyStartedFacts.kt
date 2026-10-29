package com.qkt.observe.insights

import com.qkt.dsl.StrategySource
import com.qkt.dsl.ast.StreamDecl

/*
 * The parts of a `strategy.started` payload every way of starting a strategy states alike: a daemon
 * deploy and a portfolio child build theirs from these, so the two cannot drift apart.
 */

/** `sourcePath`, `sourceSha256`, `sourceText` and `sourceTruncated` for [source]; none when it was not read. */
fun sourceFacts(source: StrategySource?): Map<String, Any?> =
    if (source == null) {
        emptyMap()
    } else {
        linkedMapOf(
            "sourcePath" to source.path,
            "sourceSha256" to source.sha256,
            "sourceText" to source.text,
            "sourceTruncated" to source.truncated,
        )
    }

/** One `streams` entry per declared stream: alias, broker, venue and qkt symbol, timeframe, warmup bars. */
fun streamFacts(streams: List<StreamDecl>): List<Map<String, Any?>> =
    streams.map {
        linkedMapOf(
            "alias" to it.alias,
            "broker" to it.broker,
            "symbol" to it.symbol,
            "qktSymbol" to it.qktSymbol,
            "timeframe" to it.timeframe,
            "warmupBars" to it.warmupBars,
        )
    }

/**
 * [metadata] with each `streams` entry given `instrument` ([InsightsVenues.instrumentFields]) and `venue`
 * for its qkt symbol, where this session knows them, so a strategy states what it trades before it trades.
 */
fun InsightsVenues.withStreamInstruments(metadata: Map<String, Any?>): Map<String, Any?> {
    val streams = metadata["streams"] as? List<*> ?: return metadata
    val stated =
        streams.map { stream ->
            val entry = stream as? Map<*, *> ?: return@map stream
            val symbol = entry["qktSymbol"] as? String ?: return@map stream
            val venue = venuePayload(describedFor(symbol)?.venueIdentity())["venue"]
            val instrument = instrumentFields(symbol)
            if (venue == null && instrument == null) return@map stream
            entry.entries.associate { it.key.toString() to it.value } +
                listOfNotNull(instrument?.let { "instrument" to it }, venue?.let { "venue" to it })
        }
    return metadata + ("streams" to stated)
}
