package com.qkt.cli

import com.qkt.marketdata.store.LocalBarStore
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDate
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FetchCommandTest {
    /**
     * A gateway answering `/symbol_info/<symbol>` from its path (the fetcher reads it for the point and for the
     * symbol's category) and every other request from [bodies] in order.
     */
    private fun gateway(vararg bodies: String): MockWebServer {
        val queue = java.util.concurrent.ConcurrentLinkedQueue(bodies.toList())
        return MockWebServer().apply {
            dispatcher =
                object : okhttp3.mockwebserver.Dispatcher() {
                    override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                        if (request.path.orEmpty().startsWith("/symbol_info/")) {
                            MockResponse().setBody("""{"point":"0.00001","path":"Forex\\\\EURUSDm"}""")
                        } else {
                            MockResponse().setBody(queue.poll() ?: "[]")
                        }
                }
            start()
        }
    }

    private fun MockWebServer.paths(): List<String> = (0 until requestCount).map { takeRequest().path.orEmpty() }

    @Test
    fun `uppercase target resolves lowercase profile and records closed empty day`(
        @TempDir tmp: Path,
    ) {
        val server = gateway("[]", "[]")
        try {
            val config = tmp.resolve("qkt.config.yaml")
            Files.writeString(
                config,
                """
                brokers:
                  exness:
                    type: mt5
                    gateway_url: ${server.url("/").toString().trimEnd('/')}
                """.trimIndent(),
            )
            val args =
                Args(
                    arrayOf(
                        "fetch",
                        "EXNESS:EURUSD",
                        "--tf",
                        "1m",
                        "--from",
                        "2026-07-10",
                        "--to",
                        "2026-07-11",
                        "--config",
                        config.toString(),
                        "--data-root",
                        tmp.toString(),
                    ),
                )

            assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)

            val store = LocalBarStore(tmp)
            val openDay = LocalDate.parse("2026-07-10")
            val day = LocalDate.parse("2026-07-11")
            assertThat(store.hasDay("EXNESS", "EURUSD", "1m", openDay)).isFalse()
            assertThat(store.hasDay("EXNESS", "EURUSD", "1m", day)).isTrue()
            assertThat(store.readDay("EXNESS", "EURUSD", "1m", day)).isEmpty()
            assertThat(store.readManifest("EXNESS", "EURUSD", "1m").ranges).hasSize(1)
            val paths = server.paths()
            assertThat(paths.filter { it.startsWith("/symbol_info/") }).isNotEmpty.allMatch {
                it ==
                    "/symbol_info/EURUSDm"
            }
            assertThat(paths.filterNot { it.startsWith("/symbol_info/") }).hasSize(2).allMatch {
                "/fetch_data_range" in
                    it
            }
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `a UTC day that has not ended is not stored, so a later fetch takes it whole`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(config, "brokers:\n  exness:\n    type: mt5\n    gateway_url: http://127.0.0.1:9\n")
        val noon =
            java.time.Instant
                .parse("2026-07-11T12:00:00Z")
                .toEpochMilli()
        val args =
            Args(
                arrayOf(
                    "fetch",
                    "EXNESS:EURUSD",
                    "--tf",
                    "1m",
                    "--from",
                    "2026-07-11",
                    "--to",
                    "2026-07-11",
                    "--config",
                    config.toString(),
                    "--data-root",
                    tmp.toString(),
                ),
            )

        assertThat(FetchCommand(args, com.qkt.common.FixedClock(noon)).run()).isEqualTo(ExitCodes.SUCCESS)

        assertThat(LocalBarStore(tmp).hasDay("EXNESS", "EURUSD", "1m", LocalDate.parse("2026-07-11"))).isFalse()
    }

    @Test
    fun `a day whose bars stop while the market is open is not stored, so a later fetch takes it whole`(
        @TempDir tmp: Path,
    ) {
        val bars0 = java.time.Instant.parse("2026-07-09T00:00:00Z")
        // MT5 serves what its terminal has cached: here the Thursday stops at 12:48.
        val bars =
            (0 until 769).joinToString(",", "[", "]") { m ->
                """{"time":"${bars0.plusSeconds(m * 60L)}","open":1.1,"high":1.1,"low":1.1,"close":1.1,""" +
                    """"tick_volume":5,"spread":10,"real_volume":0}"""
            }
        val server = gateway(bars)
        try {
            val start = bars0
            val config = tmp.resolve("qkt.config.yaml")
            Files.writeString(
                config,
                "brokers:\n  exness:\n    type: mt5\n    gateway_url: ${server.url("/").toString().trimEnd('/')}\n",
            )
            val args =
                Args(
                    arrayOf(
                        "fetch",
                        "EXNESS:EURUSD",
                        "--tf",
                        "1m",
                        "--from",
                        "2026-07-09",
                        "--to",
                        "2026-07-09",
                        "--config",
                        config.toString(),
                        "--data-root",
                        tmp.toString(),
                    ),
                )

            assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)

            val store = LocalBarStore(tmp)
            val day = LocalDate.parse("2026-07-09")
            assertThat(store.hasDay("EXNESS", "EURUSD", "1m", day)).isFalse()
        } finally {
            server.shutdown()
        }
    }

    @Test
    fun `an inverted range is an argument error`(
        @TempDir tmp: Path,
    ) {
        val args =
            Args(arrayOf("fetch", "EXNESS:EURUSD", "--tf", "1m", "--from", "2026-07-12", "--to", "2026-07-10"))

        assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.ARG_ERROR)
    }
}
