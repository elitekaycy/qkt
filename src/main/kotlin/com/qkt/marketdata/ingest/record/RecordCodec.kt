package com.qkt.marketdata.ingest.record

import java.math.BigDecimal

/**
 * A lossless one-line text form of [IngestRecord]s, used to spill an import to per-day scratch files so
 * memory holds one day at a time however large the input is. Numbers keep their exact text.
 */
internal object RecordCodec {
    fun encode(record: IngestRecord): String =
        when (record) {
            is TickRecord ->
                line(
                    "T",
                    record.timeMs,
                    record.order,
                    record.price,
                    record.volume,
                    record.bid,
                    record.ask,
                    record.bidVolume,
                    record.askVolume,
                )
            is BarRecord ->
                line(
                    "B",
                    record.timeMs,
                    record.order,
                    record.open,
                    record.high,
                    record.low,
                    record.close,
                    record.volume,
                )
            is FundingRecord -> line("F", record.timeMs, record.order, record.rate, record.price)
            is OpenInterestRecord -> line("O", record.timeMs, record.order, record.openInterest)
            is SeriesRecord ->
                line(
                    "S",
                    record.timeMs,
                    record.order,
                    BigDecimal.valueOf(record.observedMs),
                    record.value,
                )
        }

    fun decode(line: String): IngestRecord {
        val c = line.split(',')
        val time = c[1].toLong()
        val order = c[2].toLong()

        fun n(i: Int) = c[i].takeIf { it.isNotEmpty() }?.let(::BigDecimal)

        fun v(i: Int) = n(i) ?: error("scratch record missing field $i: $line")
        return when (c[0]) {
            "T" -> TickRecord(time, order, n(3), n(4), n(5), n(6), n(7), n(8))
            "B" -> BarRecord(time, order, v(3), v(4), v(5), v(6), v(7))
            "F" -> FundingRecord(time, order, v(3), n(4))
            "O" -> OpenInterestRecord(time, order, v(3))
            "S" -> SeriesRecord(time, order, v(3).longValueExact(), v(4))
            else -> error("unknown scratch record: $line")
        }
    }

    private fun line(
        tag: String,
        time: Long,
        order: Long,
        vararg values: BigDecimal?,
    ): String =
        buildString {
            append(tag)
                .append(',')
                .append(time)
                .append(',')
                .append(order)
            for (v in values) append(',').append(v?.toPlainString().orEmpty())
        }
}
