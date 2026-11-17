package com.qkt.cli

import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandErrorsTest : SweepCommandFixture() {
    @Test
    fun `a swept parameter the strategy does not have is a compile error`(
        @TempDir dir: Path,
    ) {
        FakeXauFetcher.fetch(
            "XAUUSD",
            LocalDate.parse("2026-06-04"),
            dir.resolve("data/symbols/XAUUSD/2026-06-04.csv.gz"),
        )
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--data-root",
                    dir.resolve("data").toString(),
                    "--param",
                    "nope=1,2",
                ),
            )

        assertThatThrownBy { captureCliRun { SweepCommand(args, fetcherOverride = FakeXauFetcher).run() } }
            .isInstanceOf(com.qkt.dsl.compile.CompileError::class.java)
            .hasMessageContaining("unknown parameter 'nope'; strategy declares: [fast]")
    }
}
