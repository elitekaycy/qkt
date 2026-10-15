package com.qkt.marketdata.ingest.check

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class QuoteBarPairingTest {
    @TempDir
    lateinit var dir: Path

    private val header = "Gmt time,Open,High,Low,Close,Volume\n"

    private fun file(
        name: String,
        rows: String,
    ): Path =
        dir.resolve("in").resolve(name).also { Files.createDirectories(it.parent) }.also {
            Files.writeString(
                it,
                header + rows,
            )
        }

    @Test
    fun `the side is read from Dukascopy-style file names`() {
        assertThat(
            QuoteBarPairing.sideOf("EURUSD_Candlestick_1_M_BID_01.01.2024-31.01.2024.csv"),
        ).isEqualTo(QuoteSide.BID)
        assertThat(QuoteBarPairing.sideOf("eurusd-m1-ask-2024.csv")).isEqualTo(QuoteSide.ASK)
        assertThat(QuoteBarPairing.sideOf("EURUSD_BIDASK.csv")).isNull()
        assertThat(QuoteBarPairing.sideOf("BID_vs_ASK.csv")).isNull()
    }

    @Test
    fun `a bid file and an ask file import as one series of mid bars`() {
        val bid =
            file(
                "EURUSD_Candlestick_1_M_BID_2024.csv",
                "02.01.2024 00:00:00.000,1.10370,1.10390,1.10360,1.10380,100\n" +
                    "02.01.2024 00:01:00.000,1.10380,1.10400,1.10370,1.10390,50\n",
            )
        val ask =
            file(
                "EURUSD_Candlestick_1_M_ASK_2024.csv",
                "02.01.2024 00:00:00.000,1.10372,1.10394,1.10362,1.10383,120\n" +
                    "02.01.2024 00:01:00.000,1.10383,1.10402,1.10372,1.10393,60\n",
            )
        val root = dir.resolve("root")

        val report = IngestFixtures.import(root, listOf(bid, ask), "DUKASCOPY:EURUSD")

        assertThat(report.warnings).anyMatch { it.startsWith("bid and ask files were paired") }
        assertThat(
            IngestFixtures.firstStoredRow(root),
        ).isEqualTo("1704153600000,1.10371000,1.10392000,1.10361000,1.10381500,220.00000000")
    }

    @Test
    fun `a bar with no partner on the other side refuses`() {
        val bid =
            file("X_BID_.csv", "02.01.2024 00:00:00.000,1.1,1.2,1.0,1.1,1\n02.01.2024 00:01:00.000,1.1,1.2,1.0,1.1,1\n")
        val ask = file("X_ASK_.csv", "02.01.2024 00:00:00.000,1.1,1.2,1.0,1.1,1\n")

        assertThatThrownBy { IngestFixtures.import(dir.resolve("root"), listOf(bid, ask), "DUKASCOPY:EURUSD") }
            .isInstanceOf(ImportRefusal::class.java)
            .satisfies({ assertThat((it as ImportRefusal).lines.single()).contains("found 1 bid and 0 ask") })
    }
}
