package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt list` and `qkt stop` when the daemon is missing, refuses, or answers something unexpected. */
class ListStopCommandErrorsTest {
    @TempDir
    lateinit var tmp: Path

    private fun client(
        list: () -> String = { "[]" },
        stop: () -> String = { "{}" },
    ) = object : ControlClient(StateDir.resolve(tmp.toString())) {
        override fun list(): String = list()

        override fun stop(
            name: String,
            flatten: Boolean,
            timeoutMs: Long?,
        ): String = stop()
    }

    private fun list(
        vararg flags: String,
        body: () -> String,
    ) = captureCliRun {
        ListCommand(Args(arrayOf("list", "--state-dir", tmp.toString(), *flags))) { client(list = body) }.run()
    }

    private fun stop(
        vararg flags: String,
        body: () -> String,
    ) = captureCliRun {
        StopCommand(Args(arrayOf("stop", "alpha", "--state-dir", tmp.toString(), *flags))) { client(stop = body) }.run()
    }

    @Test
    fun `list without a daemon is a user error carrying the reason`() {
        val run = list { throw ControlClient.NoDaemonRunningException("no daemon running (no control.port)") }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: no daemon running (no control.port)\n")
        assertThat(run.out).isEmpty()
    }

    @Test
    fun `list reports the daemon's refusal with its code and body`() {
        val run = list { throw ControlClient.DaemonError(503, "starting") }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: list failed (503): starting\n")
    }

    @Test
    fun `list prints a reply that is not an array as it came`() {
        val run = list { """{"status":"degraded"}""" }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).isEqualTo("{\"status\":\"degraded\"}\n")
    }

    @Test
    fun `list json prints the daemon's reply untouched`() {
        val run = list("--json") { """[{"name":"alpha"}]""" }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).isEqualTo("[{\"name\":\"alpha\"}]\n")
    }

    @Test
    fun `list groups children under their book, fills missing columns and explains a halted book`() {
        val run =
            list {
                """[{"name":"zeta","uptimeMs":3723000,"port":4100,"trades":7,"state":"running"},
                    {"name":"book/b","kind":"child","parent":"book"},
                    {"name":"book","kind":"portfolio","halted":true,"haltScope":"day","haltReason":"daily loss"},
                    {"name":"calm","kind":"portfolio","halted":false},
                    {"name":"bare","kind":"portfolio","halted":true},
                    7]"""
            }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        val rows = run.out.lines()
        assertThat(rows[0]).startsWith("NAME").endsWith("STATE")
        assertThat(rows.drop(1).take(5).map { it.trim().split(Regex("\\s+")) }).containsExactly(
            listOf("bare", "portfolio", "00:00:00", "-", "-", "halted"),
            listOf("book", "portfolio", "00:00:00", "-", "-", "halted"),
            listOf("book/b", "child", "00:00:00", "-", "-", "?"),
            listOf("calm", "portfolio", "00:00:00", "-", "-", "?"),
            listOf("zeta", "strategy", "01:02:03", "4100", "7", "running"),
        )
        assertThat(rows[3]).startsWith("  book/b")
        assertThat(rows.drop(6)).containsExactly(
            "portfolio bare halted (?): ",
            "portfolio book halted (day): daily loss",
            "",
        )
    }

    @Test
    fun `stop without a daemon is a user error carrying the reason`() {
        val run = stop { throw ControlClient.NoDaemonRunningException("no daemon running") }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: no daemon running\n")
    }

    @Test
    fun `stop names an unknown strategy and reports any other refusal with its code`() {
        val unknown = stop { throw ControlClient.DaemonError(404, "") }
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err).isEqualTo("qkt: error: unknown strategy: alpha\n")

        val refused = stop { throw ControlClient.DaemonError(409, "already stopping") }
        assertThat(refused.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(refused.err).isEqualTo("qkt: error: stop failed (409): already stopping\n")
    }

    @Test
    fun `stop treats a reply it cannot read as a clean stop`() {
        val run = stop { "ok" }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).isEqualTo("[INFO] stopped alpha\n")
    }

    @Test
    fun `a not flat stop without held or detail says unknown and json prints the reply`() {
        val bare = stop { """{"state":"not_flat"}""" }
        assertThat(bare.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(bare.err).isEqualTo(
            "qkt: error: alpha is not flat, and it no longer trades. Held: unknown\nqkt: \n",
        )

        val json = stop("--json") { """{"state":"not_flat"}""" }
        assertThat(json.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(json.out).isEqualTo("{\"state\":\"not_flat\"}\n")
        assertThat(json.err).isEmpty()
    }
}
