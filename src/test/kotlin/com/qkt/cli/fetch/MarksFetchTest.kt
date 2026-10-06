package com.qkt.cli.fetch

import com.qkt.cli.Args
import com.qkt.cli.ExitCodes
import com.qkt.cli.FetchCommand
import com.qkt.common.FixedClock
import com.qkt.marketdata.marks.MarkStore
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

/** `qkt fetch --marks` stores a gateway account's mark history, every page of it, one whole UTC day a file. */
class MarksFetchTest {
    private val code = "BTC_USDC-PERPETUAL"
    private val target = "DERIBIT:BTC_USDC_PERPETUAL"
    private val minute = 60_000L
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
                """"volume_step":"0.001","volume_min":"0.001"}]"""
        }
        val from = url.queryParameter("from")!!.toLong()
        val to = url.queryParameter("to")!!.toLong()
        // Pages of half a day: one report a window, a minute in each of two windows, mark only in the second.
        val end = minOf(to, from + day / 2)
        val first = """{"time":${from + 59_999},"mark":"86432.49","index":"86403.5"}"""
        val marks = """[$first,{"time":${from + minute + 1},"mark":"86440"}]"""
        return """{"marks":$marks${if (end < to) ",\"next\":$end" else ""}}"""
    }

    @AfterEach
    fun stop() = server.shutdown()

    private fun fetch(
        tmp: Path,
        vararg extra: String,
    ): Int {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            "brokers:\n  deribit:\n    type: gateway\n    gateway_url: ${server.url("/").toString().trimEnd('/')}\n" +
                "    api_key: secret\n    expected_adapter: fake\n    expected_account_login: \"7\"\n    expected_trade_mode: demo\n",
        )
        val args = arrayOf("fetch", target, "--marks", "--config", "$config", "--data-root", "${tmp.resolve("data")}")
        return FetchCommand(Args(args + extra), FixedClock(time = oct3 + day + day / 2)).run()
    }

    @Test
    fun `each whole day is read page by page and stored in its own file at the window asked`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "--tf", "1m", "--from", "2026-10-03", "--to", "2026-10-04")).isEqualTo(ExitCodes.SUCCESS)

        val store = MarkStore(tmp.resolve("data"))
        val stored = store.read(target, minute, LocalDate.parse("2026-10-03"))!!
        assertThat(stored.map { it.timeMs }).containsExactly(
            oct3 + 59_999,
            oct3 + minute + 1,
            oct3 + day / 2 + 59_999,
            oct3 + day / 2 + minute + 1,
        )
        assertThat(stored[1].index).isNull()
        assertThat(store.has(target, minute, LocalDate.parse("2026-10-04"))).isFalse
        assertThat(asked.filter { it.startsWith("/v1/marks") })
            .containsExactly(
                "/v1/marks?symbol=$code&window_ms=60000&from=$oct3&to=${oct3 + day}",
                "/v1/marks?symbol=$code&window_ms=60000&from=${oct3 + day / 2}&to=${oct3 + day}",
            )
    }

    @Test
    fun `marks need a window that divides a day into whole minutes`(
        @TempDir tmp: Path,
    ) {
        assertThat(fetch(tmp, "--from", "2026-10-03", "--to", "2026-10-03")).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(
            fetch(tmp, "--tf", "30s", "--from", "2026-10-03", "--to", "2026-10-03"),
        ).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(asked).isEmpty()
    }
}
