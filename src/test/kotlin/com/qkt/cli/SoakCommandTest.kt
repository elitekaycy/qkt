package com.qkt.cli

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakCommandTest {
    @Test
    fun `report derives a verifier-compatible attestation from retained evidence`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
        val output = tmp.resolve("evidence/attestation.json")
        Files.writeString(
            health,
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":0}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(golden)

        val code = SoakCommand(args(health, reconciliation, golden, output)).run()

        assertThat(code).isEqualTo(ExitCodes.SUCCESS)
        val report = Json.parseToJsonElement(Files.readString(output)).jsonObject
        assertThat(report["testingSha"]!!.jsonPrimitive.content).isEqualTo(TESTING_SHA)
        assertThat(report["image"]!!.jsonPrimitive.content).isEqualTo(IMAGE)
        assertThat(report["status"]!!.jsonPrimitive.content).isEqualTo("pass")
        assertThat(report["artifactSha256"].toString()).contains("health", "journal", "reconciliation")
        assertThat(output.resolveSibling("paper-soak-health.jsonl")).exists()
        assertThat(output.resolveSibling("paper-soak-golden.zip")).exists()
        assertThat(output.resolveSibling("paper-soak-reconciliation.json")).exists()
    }

    @Test
    fun `report fails closed when health evidence contains dropped ticks`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
        val output = tmp.resolve("attestation.json")
        Files.writeString(
            health,
            """{"status":"ok","perStrategy":[{"name":"alpha","running":true,"droppedTicks":1}]}""" +
                "\n",
        )
        Files.writeString(reconciliation, """{"clean":true}""")
        writeGolden(golden)

        val code = SoakCommand(args(health, reconciliation, golden, output)).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }

    @Test
    fun `report fails closed when placement outcome is unknown`(
        @TempDir tmp: Path,
    ) {
        val health = tmp.resolve("health.jsonl")
        val reconciliation = tmp.resolve("reconciliation.json")
        val golden = tmp.resolve("golden.zip")
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

        val code = SoakCommand(args(health, reconciliation, golden, output)).run()

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

        val code = SoakCommand(args(health, reconciliation, golden, output)).run()

        assertThat(code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(output).doesNotExist()
    }

    private fun args(
        health: Path,
        reconciliation: Path,
        golden: Path,
        output: Path,
    ) = Args(
        arrayOf(
            "soak",
            "report",
            "alpha",
            "--testing-sha",
            TESTING_SHA,
            "--image",
            IMAGE,
            "--started-at",
            "2026-08-07T12:00:00Z",
            "--completed-at",
            "2026-08-09T12:00:00Z",
            "--trading-days",
            "2",
            "--health",
            health.toString(),
            "--reconciliation",
            reconciliation.toString(),
            "--golden",
            golden.toString(),
            "--out",
            output.toString(),
        ),
    )

    private fun writeGolden(
        path: Path,
        gatewayRecord: String =
            """{"v":1,"ts":1500,"method":"POST","path":"/order","responseCode":200,"responseBody":"{\"result\":{\"retcode\":10009}}"}
                |
            """.trimMargin(),
    ) {
        val engine =
            """{"v":1,"ts":1000,"eventType":"com.qkt.events.TickEvent"}
                |{"v":1,"ts":2000,"eventType":"com.qkt.events.BrokerEvent.OrderFilled"}
                |
            """.trimMargin()
        val entries = mapOf("engine/audit.jsonl" to engine, "gateway/demo/transport.jsonl" to gatewayRecord)
        val manifest =
            buildString {
                append(
                    """{"schemaVersion":1,"kind":"MT5_GOLDEN_CAPTURE","session":"alpha","counts":{"ticks":1,"fills":1,"gatewayExchanges":1,"linkedPlacements":1},"entries":[""",
                )
                entries.entries.forEachIndexed { index, (name, contents) ->
                    if (index > 0) append(',')
                    append("""{"path":"$name","records":1,"sha256":"${sha256(contents)}"}""")
                }
                append("]}")
            }
        ZipOutputStream(Files.newOutputStream(path)).use { zip ->
            for ((name, contents) in entries + ("manifest.json" to manifest)) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(contents.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
    }

    private fun sha256(value: String): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TESTING_SHA = "0123456789abcdef0123456789abcdef01234567"
        private const val IMAGE =
            "ghcr.io/example/qkt@sha256:0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    }
}
