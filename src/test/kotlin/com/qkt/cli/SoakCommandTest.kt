package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakCommandTest : SoakCommandFixture() {
    @Test
    fun `report derives a verifier-compatible attestation from retained evidence`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
        val coverage = tmp.resolve("coverage.json")
        val parity = tmp.resolve("parity.json")
        val insights = tmp.resolve("insights.json")
        val output = tmp.resolve("evidence/attestation.json")
        Files.writeString(
            health,
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":0}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(golden)
        writeParityEvidence(coverage, parity, insights)

        val code = SoakCommand(args(health, reconciliation, golden, coverage, parity, insights, output)).run()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertThat(report["testingSha"]!!.jsonPrimitive.content).isEqualTo(TESTING_SHA)
        assertThat(report["image"]!!.jsonPrimitive.content).isEqualTo(IMAGE)
        assertThat(report["status"]!!.jsonPrimitive.content).isEqualTo("pass")
        assertThat(report["artifactSha256"].toString()).contains("health", "journal", "reconciliation")
        assertThat(output.resolveSibling("paper-soak-health.jsonl")).exists()
        assertThat(output.resolveSibling("paper-soak-golden.zip")).exists()
        assertThat(output.resolveSibling("paper-soak-reconciliation.json")).exists()
        assertThat(output.resolveSibling("paper-soak-coverage.json")).exists()
        assertThat(output.resolveSibling("paper-soak-parity.json")).exists()
        assertThat(output.resolveSibling("paper-soak-insights.json")).exists()
    }

    @Test
    fun `report fails closed when health evidence contains dropped ticks`(
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
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":1}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(golden)
        writeParityEvidence(coverage, parity, insights)

        val code = SoakCommand(args(health, reconciliation, golden, coverage, parity, insights, output)).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }
}
