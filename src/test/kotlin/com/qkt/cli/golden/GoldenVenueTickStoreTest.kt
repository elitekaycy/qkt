package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenVenueTickStoreTest {
    @Test
    fun `two venues quoting the same bare symbol materialize into their own venue stores`(
        @TempDir tmp: Path,
    ) {
        // Matrix K01: a share CFD on MT5 beside the same share on Alpaca. Keyed by bare symbol they collide.
        val audit =
            listOf(
                journalTick(1, 1_000, "EXNESS:AAPL", "270.10"),
                journalTick(2, 1_500, "ALPACA:AAPL", "270.05"),
            )

        val root = captureAndMaterialize(tmp, audit)

        assertThat(Files.readString(root.resolve("ticks/EXNESS/AAPL/1970-01-01.csv"))).contains("1000,AAPL,270.10,")
        assertThat(Files.readString(root.resolve("ticks/ALPACA/AAPL/1970-01-01.csv"))).contains("1500,AAPL,270.05,")
        assertThat(root.resolve("symbols/AAPL")).doesNotExist()
    }

    @Test
    fun `a bare symbol only one venue quotes is written to the bare store too`(
        @TempDir tmp: Path,
    ) {
        val audit =
            listOf(journalTick(1, 1_000, "EXNESS:XAUUSD", "2000"), journalTick(2, 2_000, "EXNESS:XAUUSD", "2001"))

        val root = captureAndMaterialize(tmp, audit)

        assertThat(root.resolve("ticks/EXNESS/XAUUSD/1970-01-01.csv")).exists()
        assertThat(root.resolve("ticks/EXNESS/XAUUSD/manifest.json")).exists()
        assertThat(root.resolve("symbols/XAUUSD/1970-01-01.csv")).exists()
        assertThat(root.resolve("symbols/XAUUSD/manifest.json")).exists()
    }
}
