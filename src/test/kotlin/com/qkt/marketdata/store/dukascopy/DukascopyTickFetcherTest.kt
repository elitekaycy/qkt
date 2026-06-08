package com.qkt.marketdata.store.dukascopy

import com.qkt.marketdata.CsvTickFeed
import com.qkt.marketdata.Tick
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

class DukascopyTickFetcherTest {
    private fun lzmaRecord(msOffset: Int): ByteArray {
        val raw = ByteArrayOutputStream()
        DataOutputStream(raw).apply {
            writeInt(msOffset)
            writeInt(2_345_670)
            writeInt(2_345_650)
            writeFloat(1f)
            writeFloat(1f)
        }
        val rawBytes = raw.toByteArray()
        val out = ByteArrayOutputStream()
        LZMAOutputStream(out, LZMA2Options(), rawBytes.size.toLong()).use { it.write(rawBytes) }
        return out.toByteArray()
    }

    private inner class FakeDownloader(
        private val present: Set<Int>,
    ) : HourDownloader {
        override fun download(
            instrument: String,
            day: LocalDate,
            hour: Int,
        ): ByteArray? = if (hour in present) lzmaRecord(hour * 1000) else null
    }

    private fun readAll(target: Path): List<Tick> {
        val feed = CsvTickFeed(target)
        return buildList {
            while (true) add(feed.next() ?: break)
        }.also { feed.close() }
    }

    @Test
    fun `assembles present hours into a sorted gzip day file`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("2024-03-05.csv.gz")
        DukascopyTickFetcher(FakeDownloader(present = setOf(8, 9, 13)))
            .fetch("XAUUSD", LocalDate.of(2024, 3, 5), target)

        assertThat(Files.exists(target)).isTrue()
        val ticks = readAll(target)
        assertThat(ticks).hasSize(3)
        assertThat(ticks.map { it.timestamp }).isSorted()
        assertThat(ticks[0].symbol).isEqualTo("XAUUSD")
        assertThat(ticks[0].bid).isEqualByComparingTo("2345.65")
    }

    @Test
    fun `a day with no present hours writes an empty header-only file`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("2024-03-09.csv.gz")
        DukascopyTickFetcher(FakeDownloader(present = emptySet()))
            .fetch("XAUUSD", LocalDate.of(2024, 3, 9), target)
        assertThat(Files.exists(target)).isTrue()
        assertThat(CsvTickFeed(target).next()).isNull()
    }
}
