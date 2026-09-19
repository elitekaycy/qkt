package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SweepCommandTest : SweepCommandFixture() {
    @Test
    fun `sweep over a two-point grid runs both combos and ranks them`(
        @TempDir dir: Path,
    ) {
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
                    "fast=2,3",
                    "--rank",
                    "totalPnL",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }

    @Test
    fun `an unknown rank metric is rejected`(
        @TempDir dir: Path,
    ) {
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
                    "fast=2,3",
                    "--rank",
                    "bogus",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `tick-resolved fills are rejected before sweep fan-out`(
        @TempDir dir: Path,
    ) {
        val args =
            Args(
                arrayOf(
                    "sweep",
                    strategy(dir).toString(),
                    "--from",
                    "2026-06-04",
                    "--to",
                    "2026-06-05",
                    "--bars",
                    "--tick-fills",
                    "--param",
                    "fast=3",
                ),
            )

        assertThat(SweepCommand(args, fetcherOverride = FakeXauFetcher).run())
            .isEqualTo(ExitCodes.USER_ERROR)
    }

    @Test
    fun `sweep with a scenarios file runs each scenario`(
        @TempDir dir: Path,
    ) {
        val scenarios = dir.resolve("scenarios.yaml")
        Files.writeString(
            scenarios,
            """
            - label: fast2
              params: { fast: "2" }
            - label: fast3-sim
              params: { fast: "3" }
              broker: mt5-sim
            """.trimIndent(),
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
                    "--scenarios",
                    scenarios.toString(),
                    "--json",
                ),
            )
        val code = SweepCommand(args, fetcherOverride = FakeXauFetcher).run()
        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
    }
}
