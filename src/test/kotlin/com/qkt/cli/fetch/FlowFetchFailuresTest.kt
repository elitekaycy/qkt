package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.cli.captureCliRun
import com.qkt.common.FixedClock
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--marks`, `--funding`, `--tape` and `--liquidations` refuse a venue with no source and stop on a venue failure. */
class FlowFetchFailuresTest {
    @TempDir
    lateinit var tmp: Path

    private val code = "BTC_USDC-PERPETUAL"
    private val target = "DERIBIT:BTC_USDC_PERPETUAL"
    private val oct3 = 1_790_985_600_000L

    /** A gateway that lists its instrument and fails every history read. */
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.requestUrl!!.encodedPath == "/v1/instruments") {
                            MockResponse().setHeader("Content-Type", "application/json").setBody(
                                """[{"code":"$code","kind":"perpetual","currency":"USDC","contract_size":"1",""" +
                                    """"tick_size":"0.5","volume_step":"0.001","volume_min":"0.001"}]""",
                            )
                        } else {
                            MockResponse().setResponseCode(500).setBody("history offline")
                        }
                }
            start()
        }

    @AfterEach
    fun stop() = server.shutdown()

    private fun config(body: String): String =
        tmp
            .resolve("qkt.config.yaml")
            .also {
                Files.writeString(it, body)
            }.toString()

    private fun gatewayConfig() =
        config(
            "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${server.url("/").toString().trimEnd('/')}\n" +
                "    api_key: secret\n    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )

    private fun fetch(
        what: String,
        configPath: String,
        symbol: String = target,
    ) = captureCliRun {
        val argv =
            arrayOf("fetch", symbol, *what.split(" ").toTypedArray(), "--from", "2026-10-03", "--to", "2026-10-03") +
                arrayOf("--config", configPath, "--data-root", tmp.resolve("data").toString())
        FetchCommand(Args(argv), FixedClock(time = oct3 + 3 * 86_400_000L)).run()
    }

    @Test
    fun `a venue with no account behind it has no source`() {
        val empty = config("brokers: {}\n")
        mapOf(
            "--marks --tf 1m" to "no mark history for 'NOPE'",
            "--funding" to "no funding-rate source for 'NOPE'",
            "--tape" to "no trades for 'NOPE'",
            "--liquidations" to "no liquidations for 'NOPE'",
        ).forEach { (flags, message) ->
            val run = fetch(flags, empty, symbol = "NOPE:BTC")
            assertThat(run.code).withFailMessage(flags).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage("$flags: ${run.err}").contains(message)
        }
    }

    @Test
    fun `a failing history read stops the fetch naming the series`() {
        val cfg = gatewayConfig()
        mapOf(
            "--marks --tf 1h" to "could not fetch the marks of $target for 2026-10-03",
            "--funding" to "could not fetch the funding rates of $target",
            "--tape" to "could not fetch the trades of $target for 2026-10-03",
        ).forEach { (flags, message) ->
            val run = fetch(flags, cfg)
            assertThat(run.code).withFailMessage(flags).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage("$flags: ${run.err}").contains(message)
        }
    }

    @Test
    fun `a gateway account that cannot be opened is reported`() {
        val broken = config("brokers:\n  deribit:\n    type: gateway\n")
        listOf("--marks --tf 1m", "--funding", "--tape").forEach { flags ->
            val run = fetch(flags, broken)
            assertThat(run.code).withFailMessage(flags).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage("$flags: ${run.err}").contains("failed to open broker 'DERIBIT'")
        }
    }
}
