package com.qkt.connector.gateway

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// The VGP v1 market-data objects: live quotes (`GET /v1/quotes`) and closed bars (`GET /v1/bars`).
// Prices stay decimal strings here and are parsed into exact BigDecimals where they become ticks or candles.

/**
 * A quote event; null fields are sides or values the venue did not report. [underlying] is the price the
 * contract is valued against (an option's forward); [index] is the spot index the venue charges fees on.
 */
@Serializable
data class WireQuote(
    val symbol: String,
    val bid: String? = null,
    val ask: String? = null,
    @SerialName("bid_size") val bidSize: String? = null,
    @SerialName("ask_size") val askSize: String? = null,
    val mark: String? = null,
    @SerialName("mark_iv") val markIv: String? = null,
    val underlying: String? = null,
    val time: Long,
    val index: String? = null,
)

/** One closed bar starting at [start] (UTC epoch ms). */
@Serializable
data class WireBar(
    val start: Long,
    val open: String,
    val high: String,
    val low: String,
    val close: String,
    val volume: String,
)

/** `GET /v1/bars`: one page of [bars], oldest first; [next] is the `from` of the next page, null on the last. */
@Serializable
data class WireBars(
    val bars: List<WireBar>,
    val next: Long? = null,
)

/** A contract's [mark] and [index] as the venue reported them at [time]; null when it did not report one. */
@Serializable
data class WireMark(
    val time: Long,
    val mark: String? = null,
    val index: String? = null,
)

/** `GET /v1/marks`: one page of [marks], oldest first; [next] is the `from` of the next page, null on the last. */
@Serializable
data class WireMarks(
    val marks: List<WireMark>,
    val next: Long? = null,
)
