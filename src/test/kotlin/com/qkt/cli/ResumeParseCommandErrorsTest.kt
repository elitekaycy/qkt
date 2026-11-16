package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt resume` refusals and json, and `qkt parse` of a file that is not there. */
class ResumeParseCommandErrorsTest {
    @TempDir
    lateinit var tmp: Path

    private fun resume(
        vararg argv: String,
        answer: (String?) -> String,
    ) = captureCliRun {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun resume(name: String?): String = answer(name)
            }
        ResumeCommand(Args(arrayOf("resume", *argv, "--state-dir", tmp.toString()))) { client }.run()
    }

    @Test
    fun `resume without a daemon is a user error carrying the reason`() {
        val run = resume("alpha") { throw ControlClient.NoDaemonRunningException("no daemon running") }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: no daemon running\n")
    }

    @Test
    fun `resume names an unknown strategy, a refusal's reason, and any other failure's code`() {
        assertThat(resume("ghost") { throw ControlClient.DaemonError(404, "") }.err)
            .isEqualTo("qkt: error: unknown strategy: ghost\n")
        assertThat(resume("book/a") { throw ControlClient.DaemonError(409, """{"error":"book halted"}""") }.err)
            .isEqualTo("qkt: error: book halted\n")
        assertThat(resume("book/a") { throw ControlClient.DaemonError(409, "plain refusal") }.err)
            .isEqualTo("qkt: error: plain refusal\n")

        val failed = resume("alpha") { throw ControlClient.DaemonError(500, "boom") }
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err).isEqualTo("qkt: error: resume failed (500): boom\n")
    }

    @Test
    fun `resume with no name resumes everything and json prints the daemon's reply`() {
        val asked = mutableListOf<String?>()

        val all = resume { name -> "{}".also { asked += name } }
        assertThat(all.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(all.out).isEqualTo("[INFO] resumed all strategies\n")
        assertThat(asked).containsExactly(null)

        val json = resume("alpha", "--json") { """{"resumed":"alpha"}""" }
        assertThat(json.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(json.out).isEqualTo("{\"resumed\":\"alpha\"}\n")
    }

    @Test
    fun `parse of a missing file is a user error naming it`() {
        val missing = tmp.resolve("gone.qkt").toString()

        val run = captureCliRun { ParseCommand(Args(arrayOf("parse", missing))).run() }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: file not found: $missing\n")
    }

    @Test
    fun `halt reports a daemon failure other than an unknown strategy with its code`() {
        val client =
            object : ControlClient(StateDir.resolve(tmp.toString())) {
                override fun halt(name: String?): String = throw ControlClient.DaemonError(503, "draining")
            }

        val run =
            captureCliRun {
                HaltCommand(
                    Args(arrayOf("halt", "alpha", "--state-dir", tmp.toString())),
                ) { client }.run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).isEqualTo("qkt: error: halt failed (503): draining\n")
    }
}
