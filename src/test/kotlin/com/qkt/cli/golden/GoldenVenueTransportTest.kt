package com.qkt.cli.golden

import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipFile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class GoldenVenueTransportTest {
    @Test
    fun `a gateway leg's transport journal travels in the bundle under venues, clipped to the session`(
        @TempDir tmp: Path,
    ) {
        val venue = tmp.resolve("state/gateway-transport-journal/PAPER/transport-1970-01-01.jsonl")
        Files.createDirectories(venue.parent)
        Files.writeString(
            venue,
            listOf(
                """{"v":1,"ts":10,"seq":0,"profile":"PAPER","method":"GET","path":"/v1/health","responseCode":200,"durationMs":1}""",
                """{"v":1,"ts":1500,"seq":1,"profile":"PAPER","method":"POST","path":"/v1/orders",""" +
                    """"engineOrderId":"dsl-a--1","requestBody":"{}","responseCode":201,"durationMs":3}""",
                """{"v":1,"ts":1600,"seq":2,"profile":"PAPER","method":"EVENT","path":"/v1/stream",""" +
                    """"engineOrderId":"dsl-a--1","responseBody":"{}","durationMs":0}""",
            ).joinToString("\n", postfix = "\n"),
        )
        val audit = listOf(journalTick(1, 1_000, "PAPER:BTC_USDC_PERPETUAL"), journalTick(2, 2_000, "EXNESS:BTCUSD"))

        val root = captureAndMaterialize(tmp, audit)

        ZipFile(tmp.resolve("golden.zip").toFile()).use { zip ->
            val text = zip.getInputStream(zip.getEntry("venues/PAPER/transport-1970-01-01.jsonl")).reader().readText()
            assertThat(text.lines().filter(String::isNotBlank)).hasSize(2)
            assertThat(text).contains("\"engineOrderId\":\"dsl-a--1\"").doesNotContain("/v1/health")
            val manifest =
                Json.parseToJsonElement(
                    zip.getInputStream(zip.getEntry("manifest.json")).reader().readText(),
                )
            val counts = manifest.jsonObject.getValue("counts").jsonObject
            assertThat(counts.getValue("venueExchanges").jsonPrimitive.content).isEqualTo("2")
        }
        assertThat(root.resolve("golden-replay-manifest.json")).exists()
    }
}
