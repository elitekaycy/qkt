package com.qkt.marketdata.ingest.record

import java.math.BigDecimal

/**
 * One normalised observation, in qkt's units: [timeMs] is UTC epoch milliseconds (a bar's open time, a
 * series value's first usable instant) and every number is exact. [order] is the input order (file
 * index, then line) that breaks ties between equal times, so the same input always sorts the same way.
 */
sealed interface IngestRecord {
    val timeMs: Long
    val order: Long
}

/** A tick: a quote ([bid] and [ask]) or a trade ([price]), or both. */
data class TickRecord(
    override val timeMs: Long,
    override val order: Long,
    val price: BigDecimal?,
    val volume: BigDecimal?,
    val bid: BigDecimal?,
    val ask: BigDecimal?,
    val bidVolume: BigDecimal?,
    val askVolume: BigDecimal?,
) : IngestRecord

/** A bar at its open time, prices already on qkt's mid basis when the source allowed. */
data class BarRecord(
    override val timeMs: Long,
    override val order: Long,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: BigDecimal,
) : IngestRecord

/** A perpetual's funding rate at its settlement time, with the mark price the venue charged it at, if any. */
data class FundingRecord(
    override val timeMs: Long,
    override val order: Long,
    val rate: BigDecimal,
    val price: BigDecimal?,
) : IngestRecord

/** An open-interest figure at the instant it became known. */
data class OpenInterestRecord(
    override val timeMs: Long,
    override val order: Long,
    val openInterest: BigDecimal,
) : IngestRecord

/** A published value (COT, macro) at the instant it became available; [observedMs] is the period it describes. */
data class SeriesRecord(
    override val timeMs: Long,
    override val order: Long,
    val observedMs: Long,
    val value: BigDecimal,
) : IngestRecord
