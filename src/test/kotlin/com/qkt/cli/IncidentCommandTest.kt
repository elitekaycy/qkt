package com.qkt.cli

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.zip.ZipFile
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class IncidentCommandTest {
    @Test
    fun `collect writes journal slice logs state inputs and hashes`(
        @TempDir tmp: Path,
    ) {
        val stateDir = tmp.resolve("state-dir")
        Files.createDirectories(stateDir.resolve("state/journal/alpha"))
        Files.createDirectories(stateDir.resolve("state/alpha"))
        Files.createDirectories(stateDir.resolve("logs"))
        Files.writeString(
            stateDir.resolve("state/journal/alpha/journal-2026-06-26.jsonl"),
            """
            {"ts":1782475200000,"kind":"submit","id":"o-1"}
            {"ts":1782478800000,"kind":"accepted","id":"o-1"}
            {"ts":1782482400000,"kind":"filled","id":"o-1"}
            """.trimIndent() + "\n",
        )
        Files.writeString(stateDir.resolve("state/alpha/pnl.json"), """{"realized":"12.34"}""")
        Files.writeString(stateDir.resolve("logs/alpha.log"), "engine fault\nrisk halt\n")
        val config = tmp.resolve("qkt.config.yaml")
        val strategy = tmp.resolve("alpha.qkt")
        Files.writeString(config, "runtime:\n  mode: production\n")
        Files.writeString(strategy, "STRATEGY alpha VERSION 1\n")
        val out = tmp.resolve("incident.zip")

        val (code, stdout, stderr) =
            invoke(
                "incident",
                "collect",
                "--state-dir",
                stateDir.toString(),
                "--strategy",
                "alpha",
                "--since",
                "2026-06-26T12:30:00Z",
                "--until",
                "2026-06-26T13:30:00Z",
                "--config",
                config.toString(),
                "--strategy-file",
                strategy.toString(),
                "--out",
                out.toString(),
            )

        assertThat(code).withFailMessage(stderr).isEqualTo(ExitCodes.SUCCESS)
        assertThat(stdout).contains("qkt incident collect: wrote")
        ZipFile(out.toFile()).use { zip ->
            assertThat(zip.getEntry("manifest.json")).isNotNull
            assertThat(zip.getEntry("journal-slice/alpha/journal-2026-06-26.jsonl")).isNotNull
            assertThat(zip.getEntry("logs/alpha.log")).isNotNull
            assertThat(zip.getEntry("state/alpha/pnl.json")).isNotNull
            assertThat(zip.getEntry("inputs/config.yaml")).isNotNull
            assertThat(zip.getEntry("inputs/strategy.qkt")).isNotNull

            val journal = zip.readText("journal-slice/alpha/journal-2026-06-26.jsonl")
            assertThat(journal).contains("\"kind\":\"accepted\"")
            assertThat(journal).doesNotContain("\"kind\":\"submit\"")
            assertThat(journal).doesNotContain("\"kind\":\"filled\"")

            val manifest = zip.readText("manifest.json")
            assertThat(manifest).contains("\"qktVersion\"")
            assertThat(manifest).contains("\"configHash\": \"sha256:")
            assertThat(manifest).contains("\"strategyHash\": \"sha256:")
            assertThat(manifest).contains("\"journal-slice/alpha/journal-2026-06-26.jsonl\"")
        }
    }

    @Test
    fun `unknown incident action is an argument error`() {
        val (code, _, stderr) = invoke("incident", "summon")

        assertThat(code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(stderr).contains("unknown incident action")
    }

    private fun invoke(vararg argv: String): Triple<Int, String, String> {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val origOut = System.out
        val origErr = System.err
        System.setOut(PrintStream(out))
        System.setErr(PrintStream(err))
        return try {
            val command =
                if (argv.firstOrNull() == "incident") {
                    IncidentCommand(Args(argv as Array<String>)) { Instant.parse("2026-06-26T14:00:00Z") }.run()
                } else {
                    runMain(argv as Array<String>)
                }
            Triple(command, out.toString(), err.toString())
        } finally {
            System.setOut(origOut)
            System.setErr(origErr)
        }
    }

    private fun ZipFile.readText(name: String): String =
        getInputStream(getEntry(name)).bufferedReader().use { it.readText() }
}
