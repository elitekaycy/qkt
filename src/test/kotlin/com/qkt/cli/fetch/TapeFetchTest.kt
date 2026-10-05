package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.marketdata.flow.FlowKind
import com.qkt.marketdata.flow.TapeStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `qkt fetch --tape` and `--liquidations` store a gateway account's prints, every page, one whole UTC day a file. */
class TapeFetchTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val target = "DERIBIT:BTC_USDC_PERPETUAL"
    private val day = 86_400_000L
    private val oct3 = 1_790_985_600_000L // 2026-10-03T00:00Z
    private val asked = CopyOnWriteArrayList<String>()
    private val server =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse {
                        val url = request.requestUrl!!
                        asked += url.encodedPath + "?" + url.encodedQuery.orEmpty()
                        return MockResponse().setHeader("Content-Type", "application/json").setBody(answer(url))
                    }
                }
            start()
        }

    private fun answer(url: okhttp3.HttpUrl): String {
        if (url.encodedPath == "/v1/instruments") {
            return """[{"code":"$code","kind":"perpetual","currency":"USDC","contract_size":"1","tick_size":"0.5",""" +
                """"volume_step":"0.0001","volume_min":"0.0001"}]"""
        }
        val from = url.queryParameter("from")!!.toLong()
        val to = url.queryParameter("to")!!.toLong()
        if (url.encodedPath == "/v1/liquidations") return """{"liquidations":[]}"""
        // Two pages a day: the first cut at noon, which opens the second.
        val noon = from - from % day + day / 2
        val print = """{"id":"USDC-$from","time":${from + 5},"price":"85070.2","size":"0.0011","side":"sell"}"""
        return """{"trades":[$print]${if (from < noon && noon < to) ",\"next\":$noon" else ""}}"""
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun fetch(
        tmp: Path,
        flag: String,
    ): Int {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${server.url("/").toString().trimEnd('/')}\n" +
                "    api_key: secret\n    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        val args =
            arrayOf(
                "fetch",
                target,
                flag,
                "--from",
                "2026-10-03",
                "--to",
                "2026-10-04",
                "--config",
                "$config",
                "--data-root",
                "${tmp.resolve("data")}",
            )
        return FetchCommand(Args(args), FixedClock(time = oct3 + day + day / 2)).run()
    }

    @Test
    fun `each whole day of the tape is read page by page and stored in its own file, a day not over left out`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "--tape")).isEqualTo(ExitCodes.SUCCESS)

        val store = TapeStore(tmp.resolve("data"))
        val stored = store.read(target, FlowKind.TRADES, LocalDate.parse("2026-10-03"))!!
        assertThat(stored.map { it.timeMs }).containsExactly(oct3 + 5, oct3 + day / 2 + 5)
        assertThat(stored.map { it.side }).containsOnly(Side.SELL)
        assertThat(stored.first().size.toPlainString()).isEqualTo("0.0011")
        assertThat(store.has(target, FlowKind.TRADES, LocalDate.parse("2026-10-04"))).isFalse
        assertThat(asked.filter { it.startsWith("/v1/trades") })
            .containsExactly(
                "/v1/trades?symbol=$code&from=$oct3&to=${oct3 + day}",
                "/v1/trades?symbol=$code&from=${oct3 + day / 2}&to=${oct3 + day}",
            )
    }

    @Test
    fun `liquidations are stored as their own series, a day without any stored empty`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "--liquidations")).isEqualTo(ExitCodes.SUCCESS)

        val store = TapeStore(tmp.resolve("data"))
        assertThat(store.read(target, FlowKind.LIQUIDATIONS, LocalDate.parse("2026-10-03"))).isEmpty()
        assertThat(store.has(target, FlowKind.TRADES, LocalDate.parse("2026-10-03"))).isFalse
    }
}
