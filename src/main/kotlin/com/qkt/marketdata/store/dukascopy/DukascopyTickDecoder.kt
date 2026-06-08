package com.qkt.marketdata.store.dukascopy

import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.io.ByteArrayInputStream
import java.math.BigDecimal
import java.nio.ByteBuffer
import org.tukaani.xz.LZMAInputStream

/**
 * Decodes one dukascopy hour file (`*h_ticks.bi5`).
 *
 * The file is an LZMA-compressed stream of fixed 20-byte big-endian records:
 * `int32 msFromHourStart, int32 ask, int32 bid, float32 askVolume, float32 bidVolume`. Integer
 * prices are scaled by the instrument divisor (XAUUSD ÷1000 → `2345670` = `2345.670`).
 */
object DukascopyTickDecoder {
    private const val RECORD_BYTES = 20

    /** Inflate the raw `.bi5` (LZMA-alone) bytes to the record stream. */
    fun decompress(bi5: ByteArray): ByteArray = LZMAInputStream(ByteArrayInputStream(bi5)).use { it.readBytes() }

    /**
     * Parse [decompressed] records into ticks, stamping each with `hourStartMs + msFromHourStart`
     * and scaling prices by [divisor]. [symbol] is the bare qkt symbol stamped on each tick.
     */
    fun decodeRecords(
        decompressed: ByteArray,
        hourStartMs: Long,
        divisor: Long,
        symbol: String,
    ): List<Tick> {
        val count = decompressed.size / RECORD_BYTES
        if (count == 0) return emptyList()
        val buf = ByteBuffer.wrap(decompressed) // big-endian default
        val div = BigDecimal(divisor)
        val out = ArrayList<Tick>(count)
        repeat(count) {
            val msOffset = buf.int
            val askRaw = buf.int
            val bidRaw = buf.int
            val askVol = buf.float
            val bidVol = buf.float
            val ask = BigDecimal(askRaw).divide(div, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            val bid = BigDecimal(bidRaw).divide(div, Money.CONTEXT).setScale(Money.SCALE, Money.ROUNDING)
            out.add(
                Tick(
                    symbol = symbol,
                    price =
                        bid
                            .add(ask, Money.CONTEXT)
                            .divide(BigDecimal(2), Money.CONTEXT)
                            .setScale(Money.SCALE, Money.ROUNDING),
                    timestamp = hourStartMs + msOffset,
                    volume = null,
                    bid = bid,
                    ask = ask,
                    bidVolume = BigDecimal(bidVol.toDouble()).setScale(Money.SCALE, Money.ROUNDING),
                    askVolume = BigDecimal(askVol.toDouble()).setScale(Money.SCALE, Money.ROUNDING),
                ),
            )
        }
        return out
    }
}
