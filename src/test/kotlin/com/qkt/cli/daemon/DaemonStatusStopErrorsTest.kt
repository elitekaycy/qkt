package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.cli.ExitCodes
import com.qkt.cli.captureCliRun
import java.net.InetAddress
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt daemon status` and `qkt daemon stop` against no daemon, a refusing one, and with `--json`. */
class DaemonStatusStopErrorsTest {
    @TempDir
    lateinit var tmp: Path

    private val daemon = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @AfterEach
    fun shutdown() = daemon.shutdown()

    private fun run(vararg argv: String) =
        captureCliRun { DaemonCommand(Args(arrayOf("daemon", *argv, "--state-dir", tmp.toString()))).run() }

    private fun publishPort() =
        Files.writeString(StateDir.resolve(tmp.toString()).controlPortFile, daemon.port.toString())

    @Test
    fun `status and stop without a daemon are user errors`() {
        val status = run("status")
        assertThat(status.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(status.err).startsWith("qkt: error: ").contains("no daemon")

        val stop = run("stop")
        assertThat(stop.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stop.err).startsWith("qkt: error: ").contains("no daemon")
    }

    @Test
    fun `status and stop report a refusing daemon's code and body`() {
        publishPort()
        daemon.enqueue(MockResponse().setResponseCode(500).setBody("health broken"))
        daemon.enqueue(MockResponse().setResponseCode(500).setBody("busy"))

        val status = run("status")
        assertThat(status.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(status.err).isEqualTo("qkt: error: status failed (500): health broken\n")

        val stop = run("stop")
        assertThat(stop.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(stop.err).isEqualTo("qkt: error: shutdown failed (500): busy\n")
    }

    @Test
    fun `status json prints only the health body`() {
        publishPort()
        daemon.enqueue(MockResponse().setBody("""{"status":"ok"}"""))

        val status = run("status", "--json")

        assertThat(status.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(status.out).isEqualTo("{\"status\":\"ok\"}\n")
        assertThat(daemon.takeRequest().path).isEqualTo("/health")
    }
}
