package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.cli.captureCliRun
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `--open-interest` and `--depth` refuse a bad range or a venue with no source, and stop on a gateway failure. */
class OpenInterestDepthFetchFailuresTest {
    @TempDir
    lateinit var tmp: Path

    private val target = "DERIBIT:BTC_USDC_PERPETUAL"

    /** A gateway that answers every request with a server error. */
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest) =
                        MockResponse().setResponseCode(500).setBody("offline")
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

    private fun fetch(
        flag: String,
        configPath: String,
        symbol: String = target,
        range: Array<String> = arrayOf("--from", "2026-10-03", "--to", "2026-10-03"),
    ) = captureCliRun {
        val argv =
            arrayOf("fetch", symbol, flag, *range) +
                arrayOf("--config", configPath, "--data-root", tmp.resolve("data").toString())
        FetchCommand(Args(argv)).run()
    }

    @Test
    fun `a missing range or a bad last is an argument error`() {
        val empty = config("brokers: {}\n")
        for (flag in listOf("--open-interest", "--depth")) {
            val none = fetch(flag, empty, range = emptyArray())
            assertThat(none.code).withFailMessage(flag).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(none.err).contains("qkt: need either --from + --to or --last <Nd>")

            val bad = fetch(flag, empty, range = arrayOf("--last", "week"))
            assertThat(bad.code).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(bad.err).contains("qkt: --last must be like '30d', got 'week'")
        }
    }

    @Test
    fun `a venue with no account behind it has no source`() {
        val empty = config("brokers: {}\n")

        val oi = fetch("--open-interest", empty, symbol = "NOPE:BTC")
        assertThat(oi.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(oi.err).contains("qkt: no open-interest source for 'NOPE'")

        val depth = fetch("--depth", empty, symbol = "NOPE:BTC")
        assertThat(depth.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(depth.err).contains("qkt: no depth source for 'NOPE'")
        assertThat(tmp.resolve("data")).doesNotExist()
    }

    @Test
    fun `a gateway account that cannot be opened is reported`() {
        val broken = config("brokers:\n  deribit:\n    type: gateway\n")
        for (flag in listOf("--open-interest", "--depth")) {
            val run = fetch(flag, broken)
            assertThat(run.code).withFailMessage(flag).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).withFailMessage("$flag: ${run.err}").contains("failed to open broker 'DERIBIT'")
        }
    }

    @Test
    fun `a failing gateway stops the fetch naming the series and stores nothing`() {
        val cfg =
            config(
                "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${server.url(
                    "/",
                ).toString().trimEnd('/')}\n" +
                    "    api_key: secret\n    expected_adapter: fake\n    expected_account_login: \"7\"\n" +
                    "    expected_trade_mode: demo\n",
            )

        val oi = fetch("--open-interest", cfg)
        assertThat(oi.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(oi.err).withFailMessage(oi.err).contains("qkt: could not fetch the open interest of $target:")

        val depth = fetch("--depth", cfg)
        assertThat(depth.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(depth.err).withFailMessage(depth.err).contains("qkt: could not fetch the depth of $target:")
        assertThat(tmp.resolve("data/open_interest")).doesNotExist()
    }
}
