package com.qkt.marketdata.ingest.source

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.rows.InputLayout
import com.qkt.marketdata.ingest.run.OverlapPolicy
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Inputs refused by what they are before any row is read. */
class SourceRefusalsTest {
    @TempDir
    lateinit var dir: Path

    private fun file(
        name: String,
        bytes: ByteArray = "not a binary file".toByteArray(),
    ): Path = Files.write(dir.resolve(name), bytes)

    @Test
    fun `a path that does not exist is refused naming it`() {
        val missing = dir.resolve("nope.csv")

        assertThatThrownBy { ImportSources.expand(listOf(missing)) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessage("no such file or directory: $missing")
    }

    @Test
    fun `a MetaTrader 5 cache file is refused with how to export instead`() {
        assertThatThrownBy { ImportSources.expand(listOf(file("EURUSD.hcc"))) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("a MetaTrader 5 cache")
            .hasMessageContaining("Export")
    }

    @Test
    fun `a Dukascopy candle file is refused in favour of the tick hours`() {
        val candles = file("BID_candles_min_1.bi5")

        assertThatThrownBy { Bi5Hours.input(candles, "sha", 100_000L) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("is a Dukascopy candle file")
    }

    @Test
    fun `a file without the SCID or DBN signature is refused as not that format`() {
        val notScid = file("ES.scid")
        val notDbn = file("trades.dbn")

        assertThatThrownBy { ScidRecords.input(notScid, "sha", 2) }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("does not start with SCID")
        assertThatThrownBy { DbnRecords.input(notDbn, "sha") }
            .isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("does not start with DBN")
    }

    @Test
    fun `an overlap policy parses by name in any case and refuses others`() {
        assertThat(OverlapPolicy.parse(" Skip ")).isEqualTo(OverlapPolicy.SKIP)
        assertThatThrownBy { OverlapPolicy.parse("merge") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessage("--on-overlap must be fill, refuse, skip or replace: 'merge'")
    }

    @Test
    fun `JSON rows name no tickers`() {
        assertThat(InputLayout.Json(emptyList(), null, listOf("t"), 1, null).tickers).isNull()
    }
}
