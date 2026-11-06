package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StatusCommandErrorsTest {
    @TempDir
    lateinit var tmp: Path

    /** A daemon whose every read fails with [code], except the reads named in [answers]. */
    private fun daemon(
        code: Int,
        answers: Map<String, String> = emptyMap(),
    ) = object : ControlClient(StateDir.resolve(tmp.toString())) {
        private fun answer(read: String) = answers[read] ?: throw DaemonError(code, "$read broke")

        override fun status(name: String?) = answer("status")

        override fun health() = answer("health")

        override fun list() = answer("list")

        override fun latency() = answer("latency")
    }

    private fun status(
        client: ControlClient,
        vararg argv: String,
    ) = captureCliRun { StatusCommand(Args(arrayOf("status", *argv))) { client }.run() }

    @Test
    fun `an unknown strategy and any other daemon failure are user errors`() {
        val unknown = status(daemon(404), "ghost")
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err.trim()).isEqualTo("qkt: error: unknown strategy: ghost")

        val failed = status(daemon(500))
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err.trim()).isEqualTo("qkt: error: status failed (500): status broke")
    }

    @Test
    fun `deep status is unhealthy when health or list fails`() {
        val health = status(daemon(503), "--deep")
        assertThat(health.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(health.out.trim()).isEqualTo("qkt: UNHEALTHY")
        assertThat(health.err).contains("DAEMON       /health failed (503): health broke")

        val list = status(daemon(502, mapOf("health" to "{}")), "--deep")
        assertThat(list.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(list.out.trim()).isEqualTo("qkt: UNHEALTHY")
        assertThat(list.err).contains("CONTROL      /list failed (502): list broke")
    }

    @Test
    fun `latency renders the daemon's table and reports a failed fetch`() {
        val table = status(daemon(500, mapOf("latency" to "{}")), "--latency")
        assertThat(table.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(table.out.trim()).isEqualTo("(no strategies deployed)")

        val failed = status(daemon(500), "--latency")
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err.trim()).isEqualTo("qkt: error: latency fetch failed (500): latency broke")
    }

    @Test
    fun `latency without a daemon says so`() {
        val latency =
            captureCliRun { StatusCommand(Args(arrayOf("status", "--latency", "--state-dir", tmp.toString()))).run() }
        assertThat(latency.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(latency.err).contains("no daemon running")
    }

    @Test
    fun `deep status reports a daemon that vanished before the list`() {
        val vanishing =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun health() = "{}"

                override fun list(): String = throw NoDaemonRunningException("control.port gone")
            }

        val run = status(vanishing, "--deep")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("DAEMON       not running (control.port gone)")
    }
}
