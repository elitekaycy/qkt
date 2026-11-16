package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakReportRendererTest {
    @TempDir
    lateinit var tmp: Path

    private fun artifact(
        name: String,
        text: String,
    ): Path = Files.writeString(tmp.resolve(name), text)

    private fun render(
        strategy: String,
        image: String,
    ): JsonObject {
        val parity = buildJsonObject { put("mismatches", JsonPrimitive(0)) }
        val text =
            renderSoakReport(
                strategy = strategy,
                testingSha = "abc123",
                image = image,
                startedAt = Instant.parse("2026-10-01T00:00:00Z"),
                completedAt = Instant.parse("2026-10-15T00:00:00Z"),
                tradingDays = 10,
                healthSamples = 20_160L,
                parity = ParityEvidence(durationMinutes = 60, timeframes = listOf("1m"), root = parity),
                healthArtifact = artifact("health.jsonl", "abc"),
                journalArtifact = artifact("journal.jsonl", ""),
                reconciliationArtifact = artifact("reconciliation.json", "{}"),
                coverageArtifact = artifact("coverage.json", "{}"),
                parityArtifact = artifact("parity.json", "{}"),
                insightsArtifact = artifact("insights \"final\".json", "{}"),
            )
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `the report is valid json carrying the release, window, metrics, parity and artifact digests`() {
        val report = render("gold_breakout", "ghcr.io/elitekaycy/qkt:testing")

        assertThat(report["schemaVersion"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(report["testingSha"]!!.jsonPrimitive.content).isEqualTo("abc123")
        assertThat(report["accountMode"]!!.jsonPrimitive.content).isEqualTo("demo")
        assertThat(report["startedAtUtc"]!!.jsonPrimitive.content).isEqualTo("2026-10-01T00:00:00Z")
        assertThat(report["completedAtUtc"]!!.jsonPrimitive.content).isEqualTo("2026-10-15T00:00:00Z")
        assertThat(report["tradingDays"]!!.jsonPrimitive.int).isEqualTo(10)
        assertThat(report["status"]!!.jsonPrimitive.content).isEqualTo("pass")
        assertThat(report["metrics"]!!.jsonObject["healthSamples"]!!.jsonPrimitive.long).isEqualTo(20_160L)
        assertThat(report["metrics"]!!.jsonObject["droppedTicks"]!!.jsonPrimitive.int).isZero()
        assertThat(report["parity"]!!.jsonObject["mismatches"]!!.jsonPrimitive.int).isZero()
        assertThat(report["artifacts"]!!.jsonObject["health"]!!.jsonPrimitive.content).isEqualTo("health.jsonl")
        val digests = report["artifactSha256"]!!.jsonObject
        assertThat(digests["health"]!!.jsonPrimitive.content)
            .isEqualTo("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad")
        assertThat(digests["journal"]!!.jsonPrimitive.content)
            .isEqualTo("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855")
        assertThat(digests.keys)
            .containsExactly("health", "journal", "reconciliation", "coverage", "parity", "insights")
    }

    @Test
    fun `names with quotes, backslashes and control characters stay valid json and read back unchanged`() {
        val strategy = "gold \"v2\"\\beta\n\tsecond line\r"

        val report = render(strategy, "registry\\qkt:\"x\"")

        assertThat(report["canaryStrategy"]!!.jsonPrimitive.content).isEqualTo(strategy)
        assertThat(report["image"]!!.jsonPrimitive.content).isEqualTo("registry\\qkt:\"x\"")
        assertThat(report["artifacts"]!!.jsonObject["insights"]!!.jsonPrimitive.content)
            .isEqualTo("insights \"final\".json")
    }
}
