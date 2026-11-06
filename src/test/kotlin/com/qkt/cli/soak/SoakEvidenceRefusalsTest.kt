package com.qkt.cli.soak

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakEvidenceRefusalsTest {
    @TempDir
    lateinit var tmp: Path

    private fun file(
        name: String,
        body: String,
    ): Path = tmp.resolve(name).also { Files.writeString(it, body) }

    @Test
    fun `health samples must be ok list the strategy running and report dropped ticks`() {
        fun refused(
            line: String,
            message: String,
        ) {
            val path = file("health.jsonl", "\n$line\n")
            assertThatThrownBy { inspectHealth(path, "alpha") }.hasMessage(message.replace("PATH", "$path:2"))
        }
        refused("""{"status":"degraded"}""", "unhealthy sample at PATH")
        refused("""{"status":"ok","perStrategy":[{"name":"beta"}]}""", "strategy 'alpha' missing at PATH")
        refused(
            """{"status":"ok","perStrategy":[{"name":"alpha","running":false}]}""",
            "strategy 'alpha' was not running at PATH",
        )
        refused("""{"status":"ok","perStrategy":[{"name":"alpha","running":true}]}""", "missing droppedTicks at PATH")
    }

    private val positives =
        listOf(
            "durationMinutes",
            "strategiesTested",
            "indicatorsTested",
            "mathScenariosTested",
            "dslScenariosTested",
            "orderTypesTested",
            "totalTicks",
            "totalBars",
            "fills",
            "parityComparisons",
            "insightsEvents",
            "warmupBars",
            "warmupTicks",
            "barBoundaryTransitions",
        )

    @Test
    fun `parity evidence must name its timeframes and every counter`() {
        assertThatThrownBy {
            inspectParity(
                file("p1.json", "{}"),
            )
        }.hasMessage("parity evidence missing timeframesTested")
        assertThatThrownBy { inspectParity(file("p2.json", """{"timeframesTested":["1m"]}""")) }
            .hasMessage("parity evidence missing durationMinutes")
        val allPositive = positives.joinToString(",") { "\"$it\":1" }
        assertThatThrownBy { inspectParity(file("p3.json", """{"timeframesTested":["1m"],$allPositive}""")) }
            .hasMessage("parity evidence missing parityMismatches")
    }

    private fun golden(manifest: String?): Path {
        val zip = tmp.resolve("golden-${manifest.hashCode()}.zip")
        ZipOutputStream(Files.newOutputStream(zip)).use { out ->
            out.putNextEntry(ZipEntry(if (manifest == null) "engine/a.jsonl" else "manifest.json"))
            out.write((manifest ?: "{}").toByteArray())
            out.closeEntry()
        }
        return zip
    }

    @Test
    fun `a golden bundle must carry a matching manifest with counts and complete entries`() {
        val counts = """"counts":{"ticks":1,"fills":1,"gatewayExchanges":1,"linkedPlacements":1}"""
        mapOf(
            null to "golden bundle has no manifest.json",
            """{"kind":"OTHER"}""" to "unsupported golden bundle kind",
            """{"kind":"MT5_GOLDEN_CAPTURE","session":"beta"}""" to "golden bundle session does not match 'alpha'",
            """{"kind":"MT5_GOLDEN_CAPTURE","session":"alpha"}""" to "golden bundle has no counts",
            """{"kind":"MT5_GOLDEN_CAPTURE","session":"alpha",$counts}""" to "golden bundle has no entries",
            """{"kind":"MT5_GOLDEN_CAPTURE","session":"alpha",$counts,"entries":[{}]}""" to "golden entry path missing",
            """{"kind":"MT5_GOLDEN_CAPTURE","session":"alpha",$counts,"entries":[{"path":"engine/a.jsonl"}]}""" to
                "golden entry SHA-256 missing",
        ).forEach { (manifest, message) ->
            assertThatThrownBy { inspectGolden(golden(manifest), "alpha") }.hasMessage(message)
        }
    }
}
