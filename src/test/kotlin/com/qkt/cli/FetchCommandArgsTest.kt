package com.qkt.cli

import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FetchCommandArgsTest {
    @TempDir
    lateinit var tmp: Path

    private fun fetch(vararg argv: String) =
        captureCliRun { FetchCommand(Args(arrayOf("fetch", *argv, "--data-root", tmp.toString()))).run() }

    @Test
    fun `malformed invocations are argument errors naming the problem`() {
        mapOf(
            arrayOf("XAUUSD") to "target must be 'BROKER:SYMBOL', got 'XAUUSD'",
            arrayOf("EXNESS:") to "target must be 'BROKER:SYMBOL', got 'EXNESS:'",
            arrayOf("DERIBIT:BTC", "--live") to "--live needs --chains",
            arrayOf("EXNESS:XAUUSD") to "missing required flag --tf",
            arrayOf("EXNESS:XAUUSD", "--tf", "7x") to "invalid --tf '7x'",
            arrayOf("EXNESS:XAUUSD", "--tf", "1m") to "need either --from + --to or --last <Nd>",
            arrayOf("EXNESS:XAUUSD", "--tf", "1m", "--last", "3w") to "--last must be like '30d', got '3w'",
            arrayOf("EXNESS:XAUUSD", "--tf", "1m", "--from", "July", "--to", "2026-07-11") to
                "invalid date in --from/--to",
        ).forEach { (argv, message) ->
            val run = fetch(*argv)
            assertThat(run.code).withFailMessage(argv.joinToString(" ")).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(run.err).withFailMessage("${argv.joinToString(" ")}: ${run.err}").contains(message)
        }
    }

    @Test
    fun `the local backtest store has nothing to fetch from`() {
        val run = fetch("BACKTEST:XAUUSD", "--tf", "1m", "--from", "2026-07-10", "--to", "2026-07-10")

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("BACKTEST is the local store; nothing to fetch from")
    }

    @Test
    fun `a day already on disk is skipped and a failed day stops the fetch`() {
        val gateway =
            MockWebServer().apply {
                dispatcher =
                    object : Dispatcher() {
                        override fun dispatch(request: RecordedRequest): MockResponse =
                            if (request.path.orEmpty().startsWith("/symbol_info/")) {
                                MockResponse().setBody("""{"point":"0.00001","path":"Forex\\EURUSDm"}""")
                            } else {
                                MockResponse().setResponseCode(500).setBody("terminal busy")
                            }
                    }
                start()
            }
        try {
            val config = tmp.resolve("qkt.config.yaml")
            Files.writeString(
                config,
                "brokers:\n  exness:\n    type: mt5\n    gateway_url: ${gateway.url("/").toString().trimEnd('/')}\n",
            )
            val stored = LocalDate.parse("2026-07-09")
            LocalBarStore(tmp).apply {
                writeDay("EXNESS", "EURUSD", "1m", stored, emptyList())
                recordDay("EXNESS", "EURUSD", "1m", stored)
            }

            val run =
                fetch(
                    "EXNESS:EURUSD",
                    "--tf",
                    "1m",
                    "--from",
                    "2026-07-09",
                    "--to",
                    "2026-07-10",
                    "--config",
                    config.toString(),
                )

            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.out).containsPattern("\\[1/2] 2026-07-09\\s+skipped \\(already on disk\\)")
            assertThat(run.err).containsPattern("\\[2/2] 2026-07-10\\s+FAILED:")
        } finally {
            gateway.shutdown()
        }
    }
}
