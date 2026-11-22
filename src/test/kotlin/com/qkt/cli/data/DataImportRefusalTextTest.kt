package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import com.qkt.marketdata.ingest.IngestFixtures
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataImportRefusalTextTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a refused import says why on stderr and that nothing was written`() {
        val file = IngestFixtures.sample("mt5-bars.csv", dir.resolve("in"))
        val argv =
            arrayOf(
                "data",
                "import",
                file.toString(),
                "--as",
                "BROKER:XAUUSD",
                "--tz",
                "Mars/Olympus",
                "--data-root",
                dir.resolve("root").toString(),
            )

        val run = captureCliRun { DataCommand(Args(argv)).run() }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).startsWith("qkt: ").endsWith("\n  Nothing was written.\n")
        assertThat(Files.exists(dir.resolve("root"))).isFalse()
    }
}
