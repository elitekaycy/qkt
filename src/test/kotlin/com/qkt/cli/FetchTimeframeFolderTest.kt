package com.qkt.cli

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FetchTimeframeFolderTest {
    @Test
    fun `bars fetched with --tf 60m are stored under 1h, the folder readers look in`(
        @TempDir tmp: Path,
    ) {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"point":"0.00001"}"""))
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
                        "60m",
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

            assertThat(FetchCommand(args).run()).isEqualTo(ExitCodes.SUCCESS)

            val symbolDir = tmp.resolve("bars/EXNESS/EURUSD")
            assertThat(Files.exists(symbolDir.resolve("1h/2026-07-11.csv"))).isTrue()
            assertThat(Files.exists(symbolDir.resolve("60m"))).isFalse()
        } finally {
            server.shutdown()
        }
    }
}
