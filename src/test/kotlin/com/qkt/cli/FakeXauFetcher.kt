package com.qkt.cli

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.store.DataFetcher
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPOutputStream
import kotlin.math.sin

/**
 * Test fetcher: one full day of per-minute XAUUSD ticks with an oscillating mid so EMAs cross and
 * trades fire. Covers every UTC hour, so the completeness validator passes. Use this (not the
 * constant-price fetcher) for sweep / walk-forward / research tests that need actual trades.
 */
object FakeXauFetcher : DataFetcher {
    override fun fetch(
        symbol: String,
        day: LocalDate,
        target: Path,
    ) {
        Files.createDirectories(target.parent)
        val dayStart = day.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
        val bare = symbol.substringAfter(':')
        val sb = StringBuilder(CsvTickFeed.EXPECTED_HEADER).append('\n')
        for (m in 0 until 24 * 60) {
            val ts = dayStart + m * 60_000L
            val mid = 2000.0 + 20.0 * sin(m / 30.0)
            sb.append("$ts,$bare,,,${"%.3f".format(mid - 0.1)},${"%.3f".format(mid + 0.1)},1.0,1.0\n")
        }
        GZIPOutputStream(Files.newOutputStream(target)).bufferedWriter().use { it.write(sb.toString()) }
    }
}
