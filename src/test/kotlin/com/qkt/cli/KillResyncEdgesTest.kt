package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class KillResyncEdgesTest {
    @TempDir
    lateinit var tmp: Path

    private fun client(reply: () -> String) =
        object : ControlClient(StateDir.resolve(tmp.toString())) {
            override fun kill(
                name: String?,
                flatten: Boolean,
            ) = reply()

            override fun resync(
                name: String,
                file: Path,
                dryRun: Boolean,
                ignoreMismatches: Boolean,
                waiver: String?,
                waiverReason: String?,
            ) = reply()
        }

    private fun kill(
        reply: () -> String,
        vararg argv: String,
    ) = captureCliRun { KillCommand(Args(arrayOf("kill", *argv))) { client(reply) }.run() }

    private fun resync(
        reply: () -> String,
        vararg argv: String,
    ) = captureCliRun { ResyncCommand(Args(arrayOf("resync", *argv))) { client(reply) }.run() }

    @Test
    fun `kill names an unknown strategy and any other daemon failure`() {
        val unknown = kill({ throw ControlClient.DaemonError(404, "") }, "ghost")
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err.trim()).isEqualTo("qkt: error: unknown strategy: ghost")

        val failed = kill({ throw ControlClient.DaemonError(500, "venue down") }, "alpha")
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err.trim()).isEqualTo("qkt: error: kill failed (500): venue down")
    }

    @Test
    fun `kill json prints the reply and exits on whether a flatten was verified`() {
        val verified = kill({ """{"flattenVerified":true}""" }, "alpha", "--flatten", "--json")
        assertThat(verified.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(verified.out.trim()).isEqualTo("""{"flattenVerified":true}""")

        val unverified = kill({ """{"flattenVerified":false}""" }, "alpha", "--flatten", "--json")
        assertThat(unverified.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unverified.out.trim()).isEqualTo("""{"flattenVerified":false}""")
    }

    @Test
    fun `resync names a missing file and a daemon without a control port`() {
        val missing = resync({ "{}" }, tmp.resolve("gone.qkt").toString())
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("file not found:")

        val strategy = tmp.resolve("a.qkt").also { Files.writeString(it, "STRATEGY a VERSION 1") }
        val noDaemon =
            resync({ throw ControlClient.NoDaemonRunningException("no daemon running here") }, strategy.toString())
        assertThat(noDaemon.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noDaemon.err.trim()).isEqualTo("qkt: error: no daemon running here")
    }

    @Test
    fun `resync passes a named waiver and prints a non-object reply as is`() {
        val strategy = tmp.resolve("a.qkt").also { Files.writeString(it, "STRATEGY a VERSION 1") }
        var seenWaiver: String? = null
        val capturing =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resync(
                    name: String,
                    file: Path,
                    dryRun: Boolean,
                    ignoreMismatches: Boolean,
                    waiver: String?,
                    waiverReason: String?,
                ): String {
                    seenWaiver = waiver
                    return "[\"queued\"]"
                }
            }

        val run =
            captureCliRun {
                ResyncCommand(
                    Args(arrayOf("resync", strategy.toString(), "--waive", "paper_days", "--reason", "soak")),
                ) {
                    capturing
                }.run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo("[\"queued\"]")
        assertThat(seenWaiver).isEqualTo("paper_days")
    }
}
