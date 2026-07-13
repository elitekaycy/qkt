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
    @Test
    fun `uppercase target resolves lowercase profile and records closed empty day`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"point":"0.00001"}"""))
            server.enqueue(MockResponse().setBody("[]"))
            server.enqueue(MockResponse().setBody("[]"))
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
            assertThat(server.takeRequest().path).isEqualTo("/symbol_info/EURUSDm")
            assertThat(server.takeRequest().path).contains("/fetch_data_range")
            assertThat(server.takeRequest().path).contains("/fetch_data_range")
        } finally {
            server.shutdown()
        }
    }
}
