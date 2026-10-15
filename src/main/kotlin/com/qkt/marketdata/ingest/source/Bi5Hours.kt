package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.store.dukascopy.DukascopyTickDecoder
import java.io.ByteArrayInputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Reads Dukascopy `.bi5` hour files as they are laid out on Dukascopy's datafeed and in mirrors of it,
 * `<YYYY>/<MM, from 00>/<DD>/<HH>h_ticks.bi5`: the path gives the hour, the file's records give the ticks.
 * Decoding is qkt's own [DukascopyTickDecoder], so an imported hour is exactly what `qkt backtest` would
 * have fetched. Each hour becomes rows `time,bid,ask,bidVolume,askVolume,volume` read by the
 * `dukascopy-bi5` preset. An empty file is an hour with no ticks.
 */
internal object Bi5Hours {
    private val HOUR = Regex("""(\d{4})[/\\](\d{2})[/\\](\d{2})[/\\](\d{2})h_ticks\.bi5$""", RegexOption.IGNORE_CASE)
    const val HEADER = "time,bid,ask,bidVolume,askVolume,volume"

    /** True when [path] is a `.bi5` file. */
    fun isBi5(path: Path): Boolean = path.fileName.toString().endsWith(".bi5", ignoreCase = true)

    /** The input for one hour file, or null when it holds no ticks; [divisor] scales Dukascopy's integer prices. */
    fun input(
        path: Path,
        sha256: String,
        divisor: Long?,
    ): ImportInput? {
        val text = path.toString()
        val m =
            HOUR.find(text)
                ?: throw ImportRefusal(
                    "$text: a .bi5 file holds no date of its own; " +
                        "keep Dukascopy's layout <YYYY>/<MM from 00>/<DD>/<HH>h_ticks.bi5",
                )
        val (y, month0, d, h) = m.destructured
        val hourStartMs =
            LocalDate
                .of(y.toInt(), month0.toInt() + 1, d.toInt())
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant()
                .toEpochMilli() + h.toInt() * HOUR_MS
        val bytes = Files.readAllBytes(path)
        if (bytes.isEmpty()) return null
        val scale =
            divisor
                ?: throw ImportRefusal(
                    "$text: Dukascopy prices are scaled integers; pass --digits (5 for EURUSD, 3 for XAUUSD)",
                )
        val decompressed =
            try {
                DukascopyTickDecoder.decompress(bytes)
            } catch (e: IOException) {
                throw ImportRefusal("$text is not a readable .bi5 (LZMA) file: ${e.message}")
            }
        val ticks = DukascopyTickDecoder.decodeRecords(decompressed, hourStartMs, scale, "X")
        if (ticks.isEmpty()) return null
        val csv =
            buildString {
                append(HEADER).append('\n')
                for (t in ticks) {
                    val cells =
                        listOf(
                            t.bid,
                            t.ask,
                            t.bidVolume,
                            t.askVolume,
                            t.volume,
                        ).joinToString(",") { it?.toPlainString().orEmpty() }
                    append(t.timestamp).append(',').append(cells).append('\n')
                }
            }.toByteArray()
        return ImportInput("$y/$month0/$d/${h}h_ticks.bi5", sha256) { ByteArrayInputStream(csv) }
    }

    private const val HOUR_MS = 3_600_000L
}
