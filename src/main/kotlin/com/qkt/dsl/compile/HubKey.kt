package com.qkt.dsl.compile

/**
 * Identity triple for a stream managed by [CandleHub].
 *
 * `broker` is the venue prefix (`EXNESS`, `BYBIT_SPOT`, `BACKTEST`), `symbol` is the
 * instrument, `timeframe` is the candle window (`1m`, `15m`, `1h`, ...). Two strategies
 * targeting the same triple share aggregation; differing on any field gets its own slot.
 */
data class HubKey(
    val broker: String,
    val symbol: String,
    val timeframe: String,
) {
    init {
        require(broker.isNotBlank()) { "HubKey.broker must not be blank" }
        require(symbol.isNotBlank()) { "HubKey.symbol must not be blank" }
        require(timeframe.isNotBlank()) { "HubKey.timeframe must not be blank" }
    }

    // Computed once per key, not per access: CandleHub.feed compares this against every tick's
    // symbol for every slot, so a per-access getter would rebuild "$broker:$symbol" on the hot
    // path millions of times.
    val qktSymbol: String = "$broker:$symbol"
}

/**
 * The synthetic alias under which a hub dataset's field is registered as its own stream.
 *
 * A hub alias declares a dataset; a rule reads one field of it. Registering each referenced field
 * as its own stream lets every existing mechanism -- the candle hub's slots, warmup seeding, the
 * merge -- work on hub data without any of them learning what a dataset is. The separator cannot
 * appear in a DSL alias, so a hidden entry can never collide with one an author wrote.
 */
fun hubFieldAlias(
    alias: String,
    field: String,
): String = "$alias/$field"

/**
 * True for a stream that carries a published OBSERVATION rather than a tradeable price.
 *
 * Macro series and hub datasets are both statements about the world, not quotes: they arrive at
 * irregular instants, have no bid or ask, and must become readable the moment they are published
 * rather than at the close of some arbitrary window. Two behaviours key off this -- the candle hub
 * closes an observation immediately as its own event candle, and the pipeline's malformed-tick and
 * outlier gates skip it, because a yield of 2.45 or a surprise of -0.1 is not an implausible price.
 *
 * A single predicate rather than a string test at each site: the two places that need this were
 * previously two hard-coded prefixes, which is exactly how a third venue gets added to one and
 * missed in the other.
 */
fun isObservationSymbol(qktSymbol: String): Boolean = qktSymbol.startsWith("MACRO:") || qktSymbol.startsWith("HUB:")
