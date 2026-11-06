package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class AuditTicksCommandTest {
    @TempDir
    lateinit var tmp: Path

    private val server = MockWebServer().apply { start() }

    @AfterEach
    fun stop() = server.shutdown()

    private fun config(gateway: String = server.url("/").toString().trimEnd('/')): String {
        val path = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            path,
            """
            brokers:
              exness:
                type: mt5
                gateway_url: $gateway
                server_time_zone: UTC
                retry_attempts: 0
            """.trimIndent(),
        )
        return path.toString()
    }

    private fun audit(vararg argv: String) =
        captureCliRun { AuditTicksCommand(Args(arrayOf("audit-ticks", *argv))).run() }

    /** A gateway that is ready, quotes one fixed tick stamped at its first quote, and serves [history] for the range. */
    private fun gateway(
        quote: Boolean = true,
        history: (String) -> MockResponse,
    ) {
        var tick: String? = null
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val path = request.path.orEmpty()
                    return when {
                        path.startsWith("/health/ready") -> MockResponse().setBody("""{"status":"ready"}""")
                        path.startsWith("/symbol_info_tick/") && quote -> {
                            val body = tick ?: """{"bid":1.1,"ask":1.1002,"time_msc":${System.currentTimeMillis()}}"""
                            tick = body
                            MockResponse().setBody(body)
                        }
                        path.startsWith("/copy_ticks_range") -> history(tick ?: "")
                        else -> MockResponse().setResponseCode(404)
                    }
                }
            }
    }

    private fun historyAudit(vararg extra: String) =
        audit(
            "--symbol",
            "EURUSD",
            "--duration",
            "1",
            "--settle-ms",
            "0",
            "--poll-ms",
            "200",
            "--reference",
            "mt5-history",
            "--config",
            config(),
            *extra,
        )

    @Test
    fun `history reference passes when every polled quote is in raw history and writes the artifact`() {
        gateway { tick -> MockResponse().setBody("[$tick]") }
        val out = tmp.resolve("audit/result.json")

        val run = historyAudit("--out", out.toString())

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out).contains("reference=mt5-history").contains("exact bid/ask matches:    1")
        assertThat(run.out).contains("result:                   PASS")
        assertThat(Files.readString(out)).contains("\"passed\":true")
        assertThat(run.err).contains("qkt audit-ticks: wrote $out")
    }

    @Test
    fun `history reference fails when the quote is missing from history and json prints the artifact`() {
        gateway { MockResponse().setBody("[]") }

        val run = historyAudit("--json")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out.lines()[1]).startsWith("{").contains("\"passed\":false")
    }

    @Test
    fun `history reference without live quotes or history is a user error`() {
        gateway(quote = false) { MockResponse().setBody("[]") }
        val noQuotes = historyAudit()
        assertThat(noQuotes.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noQuotes.err).contains("qkt audit-ticks: MT5 feed audit captured no live observations")

        gateway { MockResponse().setResponseCode(500) }
        val noHistory = historyAudit()
        assertThat(noHistory.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(noHistory.err).contains("MT5 raw tick history is unavailable")
    }

    @Test
    fun `an unready gateway an unknown profile and an unreadable broker config are user errors`() {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
        val unready = audit("--symbol", "EURUSD", "--config", config())
        assertThat(unready.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unready.err).contains("is not responding")

        val unknown = audit("--symbol", "EURUSD", "--mt5-profile", "nobody", "--config", config())
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err).contains("profile 'nobody' not found")

        val bad = tmp.resolve("bad.yaml")
        Files.writeString(
            bad,
            "brokers:\n  exness:\n    type: mt5\n    server_time_zone: UTC\n    server_tz_offset_hours: 2\n",
        )
        val unreadable = audit("--symbol", "EURUSD", "--config", bad.toString())
        assertThat(unreadable.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unreadable.err).contains("qkt: brokers load failed:")
    }

    @Test
    fun `invalid flags are argument errors before any config is read`() {
        assertThat(audit().err).contains("--symbol is required")
        listOf(
            arrayOf("--duration", "0") to "--duration must be positive",
            arrayOf("--poll-ms", "-1") to "--poll-ms must be positive",
            arrayOf("--settle-ms", "-1") to "--settle-ms must be non-negative",
            arrayOf("--reference", "bloomberg") to "--reference expected tradingview or mt5-history",
        ).forEach { (flags, message) ->
            val run = audit("--symbol", "EURUSD", *flags)
            assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(run.err).contains(message)
        }
    }

    @Test
    fun `a missing config file names the searched locations`() {
        withUserHome(tmp) {
            assumeTrue(Config.locate() == null, "a qkt.config.yaml outside the temp home is visible")
            val run = audit("--symbol", "EURUSD")
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).contains("no qkt.config.yaml found").contains("pass --config <path>")
        }
    }
}
