package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakCommandUnknownOutcomeTest : SoakCommandFixture() {
    @Test
    fun `report fails closed when placement outcome is unknown`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
        val coverage = tmp.resolve("coverage.json")
        val parity = tmp.resolve("parity.json")
        val insights = tmp.resolve("insights.json")
        val output = tmp.resolve("attestation.json")
        Files.writeString(
            health,
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":0}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(
            golden,
            """{"v":1,"ts":1500,"method":"POST","path":"/order","responseCode":503,"responseBody":"unavailable"}
                |
            """.trimMargin(),
        )
        writeParityEvidence(coverage, parity, insights)

        val code = SoakCommand(args(health, reconciliation, golden, coverage, parity, insights, output)).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }

    @Test
    fun `report fails closed when stop-loss modification outcome is unknown`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
        val coverage = tmp.resolve("coverage.json")
        val parity = tmp.resolve("parity.json")
        val insights = tmp.resolve("insights.json")
        val output = tmp.resolve("attestation.json")
        Files.writeString(
            health,
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":0}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(
            golden,
            """{"v":1,"ts":1500,"method":"POST","path":"/modify_sl_tp","responseCode":200}
                |
            """.trimMargin(),
        )
        writeParityEvidence(coverage, parity, insights)

        val code = SoakCommand(args(health, reconciliation, golden, coverage, parity, insights, output)).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }
}
