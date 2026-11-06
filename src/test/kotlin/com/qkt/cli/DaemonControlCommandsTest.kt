package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import com.qkt.cli.daemon.StateDir
import java.net.InetAddress
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt reconcile`, `qkt start` and `qkt logs` against a stand-in daemon control plane. */
class DaemonControlCommandsTest {
    @TempDir
    lateinit var tmp: Path

    private val daemon = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @AfterEach
    fun stop() = daemon.shutdown()

    private val client: (StateDir) -> ControlClient = { ControlClient(it, explicitPort = daemon.port) }

    private fun argv(vararg argv: String) = Args(arrayOf(*argv, "--state-dir", tmp.toString()))

    private fun reply(
        body: String,
        code: Int = 200,
    ) = daemon.enqueue(MockResponse().setResponseCode(code).setBody(body))

    @Test
    fun `reconcile exits zero when clean and two on any delta`() {
        reply("""{"clean":true}""")
        val clean = captureCliRun { ReconcileCommand(argv("reconcile", "alpha"), client).run() }
        assertThat(clean.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(clean.out.trim()).isEqualTo("""{"clean":true}""")
        assertThat(daemon.takeRequest().path).isEqualTo("/reconcile/alpha")

        reply("""{"clean":false,"deltas":[{"symbol":"XAUUSD"}]}""")
        val delta = captureCliRun { ReconcileCommand(argv("reconcile", "alpha"), client).run() }
        assertThat(delta.code).isEqualTo(2)
        assertThat(delta.out).contains("XAUUSD")
    }

    @Test
    fun `reconcile needs a name and reports daemon failures`() {
        val noName = captureCliRun { ReconcileCommand(argv("reconcile"), client).run() }
        assertThat(noName.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noName.err).contains("reconcile requires a strategy name")

        reply("boom", code = 500)
        val failed = captureCliRun { ReconcileCommand(argv("reconcile", "alpha"), client).run() }
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err).contains("reconcile failed (500): boom")

        val noDaemon = captureCliRun { ReconcileCommand(argv("reconcile", "alpha")).run() }
        assertThat(noDaemon.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noDaemon.err).contains("no daemon running")
    }

    @Test
    fun `start clears the child's stop and prints the reply`() {
        reply("""{"name":"book/a","state":"running"}""")

        val run = captureCliRun { StartCommand(argv("start", "book/a"), client).run() }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo("""{"name":"book/a","state":"running"}""")
        assertThat(daemon.takeRequest().let { it.method to it.path }).isEqualTo("POST" to "/start/book/a")
    }

    @Test
    fun `start reports a daemon refusal and a missing daemon`() {
        reply("not stopped", code = 409)
        val refused = captureCliRun { StartCommand(argv("start", "book/a"), client).run() }
        assertThat(refused.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(refused.err).contains("start failed (409): not stopped")

        val noDaemon = captureCliRun { StartCommand(argv("start", "book/a")).run() }
        assertThat(noDaemon.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noDaemon.err).contains("no daemon running")
    }

    @Test
    fun `logs prints each streamed line and passes lines since and follow`() {
        reply("2026-11-06 first\n2026-11-06 second\n")

        val run =
            captureCliRun {
                LogsCommand(argv("logs", "alpha", "--lines", "5", "--since", "2026-11-06", "--follow"), client).run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.lines()).startsWith("2026-11-06 first", "2026-11-06 second")
        assertThat(daemon.takeRequest().path).isEqualTo("/logs/alpha?lines=5&since=2026-11-06&follow=true")
    }

    @Test
    fun `logs defaults to two hundred lines`() {
        reply("")

        val run = captureCliRun { LogsCommand(argv("logs", "alpha"), client).run() }

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).isEmpty()
        assertThat(daemon.takeRequest().path).isEqualTo("/logs/alpha?lines=200")
    }

    @Test
    fun `logs names an unknown strategy other failures and a missing daemon`() {
        reply("", code = 404)
        val unknown = captureCliRun { LogsCommand(argv("logs", "ghost"), client).run() }
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err).contains("unknown strategy: ghost")

        reply("", code = 503)
        val failed = captureCliRun { LogsCommand(argv("logs", "alpha"), client).run() }
        assertThat(failed.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(failed.err).contains("logs failed (503)")

        val noDaemon = captureCliRun { LogsCommand(argv("logs", "alpha")).run() }
        assertThat(noDaemon.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noDaemon.err).contains("no daemon running")
    }
}
