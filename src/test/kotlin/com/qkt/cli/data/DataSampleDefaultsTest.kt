package com.qkt.cli.data

import com.qkt.cli.Args
import com.qkt.cli.DataCommand
import com.qkt.cli.ExitCodes
import java.nio.file.Path
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DataSampleDefaultsTest {
    @Test
    fun `the practice sample is the pinned September 2026 Binance month`() {
        val sample = SampleData.BTCUSDT_2026_09

        assertThat(sample.fileName).isEqualTo("BTCUSDT-1m-2026-09.zip")
        assertThat(sample.sha256).isEqualTo("7a021fb5981cfdb1a56457d68fa81360ab27350c981ddfe63112b2322df44b56")
        assertThat(sample.symbol).isEqualTo("BINANCE:BTCUSDT")
        assertThat(sample.window).isEqualTo("--from 2026-09-01 --to 2026-10-01")
    }

    @Test
    fun `the download client gives up connecting after 20 seconds and reading after 60`() {
        val client = defaultClient()

        assertThat(client.connectTimeoutMillis).isEqualTo(20_000)
        assertThat(client.readTimeoutMillis).isEqualTo(60_000)
    }

    @Test
    fun `an unreachable archive says the download failed and imports nothing`(
        @TempDir root: Path,
    ) {
        val server = MockWebServer().apply { start() }
        val url = server.url("/BTCUSDT-1m-2026-09.zip").toString()
        server.shutdown()
        val sample = SampleData(url, "0".repeat(64), "BINANCE:BTCUSDT", "--from a --to b", "terms")

        val run =
            com.qkt.cli.captureCliRun {
                dataSample(Args(arrayOf("data", "sample", "--data-root", root.toString())), sample, OkHttpClient())
            }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.err).contains("qkt: the download failed: ").contains("check the connection and run it again")
    }

    @Test
    fun `data sample runs the injected sample action with its arguments`() {
        var seen: Args? = null
        val args = Args(arrayOf("data", "sample"))

        val code =
            DataCommand(args) {
                seen = it
                7
            }.run()

        assertThat(code).isEqualTo(7)
        assertThat(seen).isSameAs(args)
    }
}
