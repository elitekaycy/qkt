package com.qkt.marketdata.ingest.run

import com.qkt.marketdata.ingest.ImportRefusal
import com.qkt.marketdata.ingest.IngestFixtures
import com.qkt.marketdata.ingest.plan.ImportDescriptor
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ImportNewestFirstTest {
    @TempDir
    lateinit var dir: Path

    @Test
    fun `a newest-first file whose rows carry values forward is refused`() {
        val newestFirst = IngestFixtures.sample("alphavantage-intraday.csv", dir.resolve("in"))

        assertThatThrownBy {
            IngestFixtures.import(
                dir.resolve("root"),
                listOf(newestFirst),
                "AV:IBM",
                ImportDescriptor(carryForward = true),
                dryRun = true,
            )
        }.isInstanceOf(ImportRefusal::class.java)
            .hasMessageContaining("runs newest first and its rows carry values forward")
            .hasMessageContaining("sort it first")
    }
}
