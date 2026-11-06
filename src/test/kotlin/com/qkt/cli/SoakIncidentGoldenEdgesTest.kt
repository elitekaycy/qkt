package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SoakIncidentGoldenEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private val sha = "a".repeat(40)
    private val image = "ghcr.io/elitekaycy/qkt@sha256:" + "b".repeat(64)

    private fun soak(vararg flags: String) =
        captureCliRun { SoakCommand(Args(arrayOf("soak", "report", "alpha", *flags))).run() }

    private fun soakFlags(
        testingSha: String = sha,
        img: String = image,
        tradingDays: String = "10",
    ): Array<String> {
        val health = tmp.resolve("health.json").also { Files.writeString(it, "{}") }
        return arrayOf(
            "--testing-sha",
            testingSha,
            "--image",
            img,
            "--started-at",
            "2026-10-01T00:00:00Z",
            "--completed-at",
            "2026-10-15T00:00:00Z",
            "--trading-days",
            tradingDays,
            "--health",
            health.toString(),
            "--reconciliation",
            tmp.resolve("absent-recon.json").toString(),
            "--golden",
            "g",
            "--coverage",
            "c",
            "--parity",
            "p",
            "--insights",
            "i",
            "--out",
            tmp.resolve("report").toString(),
        )
    }

    @Test
    fun `soak report refuses a malformed sha image or day count and missing evidence`() {
        mapOf(
            soakFlags(testingSha = "ABC") to "--testing-sha must be a lowercase 40-character git SHA",
            soakFlags(img = "qkt:latest") to "--image must be an immutable repository@sha256 digest",
            soakFlags(tradingDays = "ten") to "--trading-days must be an integer",
            soakFlags() to "reconciliation evidence not found: ",
        ).forEach { (flags, message) ->
            val run = soak(*flags)
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage(run.err).contains("qkt: soak report failed: $message")
        }
    }

    private fun incident(vararg flags: String) =
        captureCliRun {
            IncidentCommand(Args(arrayOf("incident", "collect", "--state-dir", tmp.toString(), *flags))) {
                Instant.parse("2026-11-06T00:00:00Z")
            }.run()
        }

    @Test
    fun `incident collect refuses an empty window and a non-positive file cap`() {
        val window =
            incident("--since", "2026-11-02", "--until", "2026-11-01", "--out", tmp.resolve("i.zip").toString())
        assertThat(window.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(window.err.trim()).isEqualTo("qkt: --since must be earlier than --until")

        val cap = incident("--max-file-bytes", "0", "--out", tmp.resolve("i.zip").toString())
        assertThat(cap.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(cap.err.trim()).isEqualTo("qkt: --max-file-bytes must be a positive integer")
    }

    @Test
    fun `incident collect reports a bundle it cannot write`() {
        val blocker = tmp.resolve("blocker").also { Files.writeString(it, "a file") }

        val run = incident("--out", blocker.resolve("i.zip").toString())

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: incident collect failed:")
    }

    @Test
    fun `golden capture without an audit journal for the session is a user error`() {
        val run =
            captureCliRun {
                GoldenCommand(
                    Args(arrayOf("golden", "capture", "--session", "alpha", "--state-dir", tmp.toString())),
                ).run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err.trim()).isEqualTo("qkt: no engine audit journal found for session 'alpha'")
    }
}
