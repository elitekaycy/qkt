package com.qkt.cli

import java.net.InetAddress
import java.nio.file.Path
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ObserveCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val daemon = MockWebServer().apply { start(InetAddress.getByName("127.0.0.1"), 0) }

    @AfterEach
    fun stop() = daemon.shutdown()

    private val status =
        """{"strategy":"hs","version":1,"uptimeMs":1,"startedAt":"2026-11-01T00:00:00Z","equity":0,""" +
            """"balance":0,"realized":0,"unrealized":0,"positions":[],"lastTrade":null}"""

    private fun daemonAnswers(statusCode: Int = 200) {
        daemon.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    when {
                        request.path.orEmpty().startsWith("/status/hs") ->
                            MockResponse().setResponseCode(statusCode).setBody(
                                if (statusCode ==
                                    200
                                ) {
                                    status
                                } else {
                                    "gone"
                                },
                            )
                        request.path.orEmpty().startsWith("/logs/hs") -> MockResponse().setBody("")
                        else -> MockResponse().setResponseCode(404)
                    }
            }
    }

    private fun observe(vararg argv: String) =
        captureCliRun {
            ObserveCommand(
                Args(
                    arrayOf(
                        "observe",
                        *argv,
                        "--control-port",
                        daemon.port.toString(),
                        "--state-dir",
                        tmp.toString(),
                    ),
                ),
            ).run()
        }

    @Test
    fun `an empty window with quiet logs and consistent pnl is a go`() {
        daemonAnswers()

        val run = observe("--strategy", "hs", "--since", "0m")

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .startsWith("qkt observe — hs — last 0m")
            .contains("[PASS] placement")
            .contains("no expected windows in range")
            .contains("[PASS] errors")
            .contains("[REVIEW] pnl")
            .contains("VERDICT: GO")
    }

    @Test
    fun `placement windows with no submits in the period fail the report`() {
        daemonAnswers()

        val run = observe("--strategy", "hs", "--since", "2d", "--windows", "0, 12@30")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out).contains("[FAIL] placement").contains("12:30 MISSED").contains("VERDICT: NO-GO")
        assertThat(daemon.takeRequest().path).isEqualTo("/status/hs")
        assertThat(daemon.takeRequest().path).startsWith("/logs/hs?lines=100000&since=")
    }

    @Test
    fun `hours and the default window schedule are accepted`() {
        daemonAnswers()

        val run = observe("--strategy", "hs", "--since", "30h")

        assertThat(run.out).contains("06:55").contains("15:55")
    }

    @Test
    fun `a daemon error is a user error`() {
        daemonAnswers(statusCode = 500)

        val run = observe("--strategy", "hs")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("daemon returned 500: gone")
    }

    @Test
    fun `no running daemon is a user error`() {
        val run =
            captureCliRun {
                ObserveCommand(Args(arrayOf("observe", "--strategy", "hs", "--state-dir", tmp.toString()))).run()
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("no daemon running")
    }

    @Test
    fun `a missing strategy or a malformed since is an argument error`() {
        val missing = captureCliRun { ObserveCommand(Args(arrayOf("observe"))).run() }
        assertThat(missing.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(missing.err).contains("--strategy")

        listOf("7w", "xd").forEach { since ->
            val bad = observe("--strategy", "hs", "--since", since)
            assertThat(bad.code).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(bad.err).contains("bad --since '$since' (use 7d/24h/30m)")
        }
    }
}
