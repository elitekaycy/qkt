package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncidentDefaultOutputTest {
    @Test
    fun `a collection with no output path writes a zip named for the moment it was taken`(
        @TempDir tmp: Path,
    ) {
        val expected = Path.of("qkt-incident-20250809T120000Z.zip")
        val argv =
            arrayOf(
                "incident",
                "collect",
                "--state-dir",
                tmp.toString(),
                "--config",
                tmp.resolve("none.yaml").toString(),
            )

        try {
            val run =
                captureCliRun {
                    IncidentCommand(Args(argv)) { Instant.parse("2025-08-09T12:00:00Z") }.run()
                }

            assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
            assertThat(expected).isRegularFile()
        } finally {
            Files.deleteIfExists(expected)
        }
    }
}
