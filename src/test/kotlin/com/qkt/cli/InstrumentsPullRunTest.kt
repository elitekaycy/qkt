package com.qkt.cli

import com.qkt.instrument.YamlInstrumentRegistry
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

class InstrumentsPullRunTest {
    @TempDir
    lateinit var tmp: Path

    private val gateway =
        MockWebServer().apply {
            dispatcher =
                object : Dispatcher() {
                    override fun dispatch(request: RecordedRequest): MockResponse =
                        if (request.path == "/symbol_info/XAUUSDm") {
                            MockResponse().setBody(
                                """{"ask":2000.01,"bid":2000,"digits":3,"point":0.001,"volume_min":0.01,""" +
                                    """"volume_step":0.01,"volume_max":50,"trade_contract_size":100}""",
                            )
                        } else {
                            MockResponse().setResponseCode(404)
                        }
                }
            start()
        }

    @AfterEach
    fun stop() = gateway.shutdown()

    private fun config(): String {
        val url = gateway.url("/").toString().trimEnd('/')
        val path = tmp.resolve("qkt.config.yaml")
        Files.writeString(path, "brokers:\n  exness:\n    type: mt5\n    gateway_url: $url\n    retry_attempts: 0\n")
        return path.toString()
    }

    private fun pull(vararg argv: String) =
        captureCliRun { InstrumentsCommand(Args(arrayOf("instruments", "pull", *argv))).run() }

    @Test
    fun `pull prints the venue spec as yaml and notes each cost the venue does not report`() {
        val run = pull("--symbols", "EXNESS:XAUUSD", "--config", config())

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out)
            .startsWith("# Venue contract specs. Pulled entries come from: ${config()}")
            .contains("instruments:\n")
            .contains("EXNESS:XAUUSD")
        assertThat(run.err)
            .contains("qkt: EXNESS:XAUUSD: commission not reported by the venue")
            .contains("qkt: EXNESS:XAUUSD: swap not reported by the venue (the gateway reports no swap)")
    }

    @Test
    fun `pull with out and a prefix writes renamed entries into the file`() {
        val out = tmp.resolve("instruments.yaml")

        val run =
            pull(
                "--symbols",
                " EXNESS:XAUUSD, ",
                "--as-prefix",
                "backtest",
                "--out",
                out.toString(),
                "--config",
                config(),
            )

        assertThat(run.code).withFailMessage(run.err).isEqualTo(ExitCodes.SUCCESS)
        assertThat(run.out.trim()).isEqualTo("qkt: wrote 1 instrument spec(s) to $out")
        val meta = YamlInstrumentRegistry.load(out).lookup("BACKTEST:XAUUSD")
        assertThat(meta?.contractSize).isEqualByComparingTo("100")
        assertThat(meta?.digits).isEqualTo(3)
    }

    @Test
    fun `a symbol no account serves or no venue reports is an argument error`() {
        val unserved = pull("--symbols", "NOBODY:XAUUSD", "--config", config())
        assertThat(unserved.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(unserved.err).contains("qkt: instruments pull: no configured account serves NOBODY:XAUUSD")

        val unreported = pull("--symbols", "EXNESS:EURUSD", "--config", config())
        assertThat(unreported.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(unreported.err).contains("reports no spec for EXNESS:EURUSD")
    }

    @Test
    fun `symbols are required and a config must be found`() {
        val noSymbols = pull("--symbols", " , ")
        assertThat(noSymbols.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(noSymbols.err).contains("--symbols is required")

        withUserHome(tmp) {
            assumeTrue(Config.locate() == null, "a qkt.config.yaml outside the temp home is visible")
            val noConfig = pull("--symbols", "EXNESS:XAUUSD")
            assertThat(noConfig.code).isEqualTo(ExitCodes.ARG_ERROR)
            assertThat(noConfig.err).contains("no qkt.config.yaml found; pass --config <path>")
        }
    }
}
