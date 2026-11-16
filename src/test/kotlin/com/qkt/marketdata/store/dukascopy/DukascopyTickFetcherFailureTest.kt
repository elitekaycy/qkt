package com.qkt.marketdata.store.dukascopy

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DukascopyTickFetcherFailureTest {
    private val noHours =
        object : HourDownloader {
            override fun download(
                instrument: String,
                day: LocalDate,
                hour: Int,
            ): ByteArray? = null
        }

    @Test
    fun `a publish that fails removes its scratch file and rethrows`(
        @TempDir tmp: Path,
    ) {
        val target = tmp.resolve("2024-03-09.csv.gz")
        Files.createDirectories(target)
        Files.writeString(target.resolve("occupant"), "x")

        assertThatThrownBy { DukascopyTickFetcher(noHours).fetch("XAUUSD", LocalDate.of(2024, 3, 9), target) }
            .isInstanceOf(java.io.IOException::class.java)

        assertThat(Files.list(tmp).use { s -> s.toList().map { it.fileName.toString() } })
            .containsExactly("2024-03-09.csv.gz")
    }
}
