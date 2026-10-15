package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.tukaani.xz.LZMA2Options
import org.tukaani.xz.LZMAOutputStream

class Bi5HoursTest {
    @TempDir
    lateinit var dir: Path

    /** A Dukascopy hour file: LZMA-alone records of (ms, ask, bid, askVolume, bidVolume), big-endian. */
    private fun bi5(vararg ticks: List<Number>): ByteArray {
        val raw = ByteBuffer.allocate(ticks.size * 20)
        ticks.forEach { t ->
            raw
                .putInt(
                    t[0].toInt(),
                ).putInt(t[1].toInt())
                .putInt(t[2].toInt())
                .putFloat(t[3].toFloat())
                .putFloat(t[4].toFloat())
        }
        val out = ByteArrayOutputStream()
        LZMAOutputStream(out, LZMA2Options(), -1L).use { it.write(raw.array()) }
        return out.toByteArray()
    }

    private fun hourFile(
        rel: String,
        bytes: ByteArray,
    ): Path = dir.resolve(rel).also { Files.createDirectories(it.parent) }.also { Files.write(it, bytes) }

    @Test
    fun `a datafeed hour file imports with no flags, its time from the zero-based month in the path`() {
        hourFile(
            "EURUSD/2024/02/06/10h_ticks.bi5",
            bi5(listOf(65, 108_712, 108_709, 1.5f, 0.75f), listOf(320, 108_713, 108_710, 2.25f, 1.0f)),
        )
        val root = dir.resolve("root")

        val report = IngestFixtures.import(root, listOf(dir.resolve("EURUSD")), "EURUSD")

        assertThat(report.preset).isEqualTo("dukascopy-bi5")
        assertThat(
            IngestFixtures.firstStoredRow(root),
        ).isEqualTo("1709719200065,EURUSD,,2.25000000,1.08709000,1.08712000,0.75000000,1.50000000")
    }

    @Test
    fun `an empty hour is a quiet hour, not an error`() {
        assertThat(Bi5Hours.input(hourFile("EURUSD/2024/02/06/03h_ticks.bi5", ByteArray(0)), "sha", 100_000)).isNull()
    }

    @Test
    fun `a bi5 outside Dukascopy's layout has no time and refuses`() {
        val loose = hourFile("ticks.bi5", bi5(listOf(1, 2, 3, 1f, 1f)))

        assertThatThrownBy {
            Bi5Hours.input(loose, "sha", 100_000)
        }.isInstanceOf(ImportRefusal::class.java).hasMessageContaining("layout")
    }

    @Test
    fun `an unknown symbol needs its digits to scale Dukascopy's integer prices`() {
        val file = hourFile("ABC/2024/02/06/10h_ticks.bi5", bi5(listOf(1, 2, 3, 1f, 1f)))

        assertThatThrownBy { Bi5Hours.input(file, "sha", null) }.hasMessageContaining("--digits")
    }

    @Test
    fun `a corrupt file refuses naming itself`() {
        val file = hourFile("EURUSD/2024/02/06/11h_ticks.bi5", byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13))

        assertThatThrownBy { Bi5Hours.input(file, "sha", 100_000) }.hasMessageContaining("not a readable .bi5")
    }
}
