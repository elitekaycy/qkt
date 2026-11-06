package com.qkt.cli

import com.qkt.cli.daemon.ControlClient
import java.net.InetAddress
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeployCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val daemon = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @AfterEach
    fun stop() = daemon.shutdown()

    private fun strategy(): String =
        tmp
            .resolve("alpha.qkt")
            .also {
                Files.writeString(it, "STRATEGY alpha VERSION 1")
            }.toString()

    private fun deploy(vararg argv: String) =
        captureCliRun {
            DeployCommand(Args(arrayOf("deploy", *argv, "--state-dir", tmp.resolve("state").toString()))) {
                ControlClient(it, explicitPort = daemon.port)
            }.run()
        }

    @Test
    fun `deploy posts the file and prints the reply as a table`() {
        daemon.enqueue(
            MockResponse().setBody(
                """{"name":"a1","port":7101,"state":"running","startedAt":"2026-11-06T00:00:00Z"}""",
            ),
        )

        val run = deploy(strategy(), "--as", "a1")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.lines()[0]).isEqualTo("NAME          PORT     STATE     STARTED")
        assertThat(
            run.out.lines()[1].split(Regex("\\s+")),
        ).containsExactly("a1", "7101", "running", "2026-11-06T00:00:00Z")
        val request = daemon.takeRequest()
        assertThat(request.path).isEqualTo("/deploy")
        assertThat(request.body.readUtf8()).contains("\"name\":\"a1\"").contains("alpha.qkt")
    }

    @Test
    fun `a bare waive with a reason and the reconcile override reach the daemon as query parameters`() {
        daemon.enqueue(MockResponse().setBody("{}"))

        val run = deploy(strategy(), "--reconcile", "ignore-mismatches", "--waive", "--reason", "known gap")

        assertThat(run.code).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.lines()[1].split(Regex("\\s+"))).containsExactly("alpha", "?", "?", "?")
        assertThat(URLDecoder.decode(daemon.takeRequest().path, Charsets.UTF_8))
            .isEqualTo("/deploy?reconcile=ignore-mismatches&waive=all&reason=known gap")
    }

    @Test
    fun `a named waiver is passed through and json prints the raw reply`() {
        daemon.enqueue(MockResponse().setBody("""{"name":"alpha","state":"running"}"""))

        val run = deploy(strategy(), "--waive", "paper_days", "--reason", "soak", "--json")

        assertThat(run.out.trim()).isEqualTo("""{"name":"alpha","state":"running"}""")
        assertThat(daemon.takeRequest().path).contains("waive=paper_days")
    }

    @Test
    fun `a reply that is not an object is printed as is`() {
        daemon.enqueue(MockResponse().setBody("[\"queued\"]"))

        assertThat(deploy(strategy()).out.trim()).isEqualTo("[\"queued\"]")
    }

    @Test
    fun `a waiver without a reason is refused before contacting the daemon`() {
        val run = deploy(strategy(), "--waive", "all")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("--waive requires --reason")
        assertThat(daemon.requestCount).isZero()
    }

    @Test
    fun `a daemon rejection and a missing file are user errors`() {
        daemon.enqueue(MockResponse().setResponseCode(409).setBody("promotion gate failed"))
        val rejected = deploy(strategy())
        assertThat(rejected.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(rejected.err).contains("deploy failed (409): promotion gate failed")

        val missing = deploy(tmp.resolve("gone.qkt").toString())
        assertThat(missing.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(missing.err).contains("file not found:")
    }

    @Test
    fun `without a running daemon deploy names the missing control port`() {
        val run =
            captureCliRun {
                DeployCommand(Args(arrayOf("deploy", strategy(), "--state-dir", tmp.resolve("empty").toString()))).run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("no daemon running")
    }
}
