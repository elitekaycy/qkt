package com.qkt.cli

import com.qkt.connector.mt5.MT5BrokerProfile
import com.qkt.connector.mt5.MT5SymbolInfo
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsCommandVerifyTest {
    @TempDir
    lateinit var tmp: Path

    private fun config(gateway: String = "http://unused"): String {
        val path = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            path,
            "data_root: ${tmp.toAbsolutePath()}\nbrokers:\n  exness:\n    type: mt5\n    gateway_url: $gateway\n",
        )
        return path.toString()
    }

    private fun instruments(vararg symbols: String) =
        Files.writeString(
            tmp.resolve("instruments.yaml"),
            "instruments:\n" +
                symbols.joinToString("") {
                    "  - qktSymbol: $it\n    contractSize: 100\n    volumeStep: 0.01\n    volumeMin: 0.01\n" +
                        "    volumeMax: 50\n    pointSize: 0.001\n    digits: 3\n    tradeStopsLevelPoints: 0\n"
                },
        )

    private fun info(
        digits: Int = 3,
        stops: Int = 0,
    ) = MT5SymbolInfo(
        ask = BigDecimal("2000.01"),
        bid = BigDecimal("2000.00"),
        digits = digits,
        point = BigDecimal("0.001"),
        tradeStopsLevel = stops,
        volumeMin = BigDecimal("0.01"),
        volumeStep = BigDecimal("0.01"),
        contractSize = BigDecimal("100"),
        volumeMax = null,
    )

    private fun verify(
        vararg argv: String,
        venue: (MT5BrokerProfile, String) -> MT5SymbolInfo? = { _, _ -> info() },
    ) = captureCliRun { InstrumentsCommand(Args(arrayOf("instruments", *argv)), venue).run() }

    @Test
    fun `text output lists each mismatched field with the yaml and venue values`() {
        instruments("EXNESS:XAUUSD")

        val run = verify("verify", "--config", config(), "--broker", "EXNESS") { _, _ -> info(digits = 2, stops = 5) }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out.lines())
            .contains(
                "MISMATCH EXNESS:XAUUSD -> exness:XAUUSDm",
                "  volumeMax: yaml=50 venue=null",
                "  digits: yaml=3 venue=2",
                "  tradeStopsLevelPoints: yaml=0 venue=5",
            )
    }

    @Test
    fun `an unavailable symbol is a mismatch and json escapes awkward symbol names`() {
        instruments("\"EXNESS:A\\\"B\\\\C\\tD\\nE\\rF\"")

        val run = verify("verify", "--config", config(), "--json") { _, _ -> null }

        assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(run.out.trim())
            .startsWith("[{\"symbol\":\"EXNESS:A\\\"B\\\\C\\tD\\nE\\rF\",\"profile\":\"exness\",")
            .contains("{\"field\":\"symbol_info\",\"expected\":\"available\",\"actual\":\"unavailable\"}")
    }

    @Test
    fun `without a venue seam the symbol rules are read from the mt5 gateway`() {
        val gateway = MockWebServer().apply { start() }
        try {
            gateway.enqueue(
                MockResponse().setBody(
                    """{"ask":2000.01,"bid":2000,"digits":3,"point":0.001,"volume_min":0.01,"volume_step":0.01,""" +
                        """"volume_max":50,"trade_contract_size":100,"trade_stops_level":0}""",
                ),
            )
            instruments("EXNESS:XAUUSD")

            val url = gateway.url("/").toString().trimEnd('/')
            val run =
                captureCliRun {
                    InstrumentsCommand(
                        Args(arrayOf("instruments", "verify", "--config", config(url))),
                    ).run()
                }

            assertThat(run.code).withFailMessage(run.out + run.err).isEqualTo(ExitCodes.SUCCESS)
            assertThat(run.out.trim()).isEqualTo("OK EXNESS:XAUUSD -> exness:XAUUSDm")
            assertThat(gateway.takeRequest().path).isEqualTo("/symbol_info/XAUUSDm")
        } finally {
            gateway.shutdown()
        }
    }

    @Test
    fun `an unknown broker and instruments of no selected broker are user errors`() {
        instruments("OTHER:XAUUSD")

        val unknown = verify("verify", "--config", config(), "--broker", "nobody")
        assertThat(unknown.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(unknown.err).contains("unknown MT5 broker profile 'nobody'")

        val none = verify("verify", "--config", config())
        assertThat(none.code).isEqualTo(ExitCodes.USER_ERROR)
        assertThat(none.err).contains("match the selected MT5 profile(s)")
    }

    @Test
    fun `an unknown action prints the usage`() {
        val run = verify("check")

        assertThat(run.code).isEqualTo(ExitCodes.ARG_ERROR)
        assertThat(run.err).contains("qkt: usage: qkt instruments verify|pull")
    }

    @Test
    fun `verify without any config is a user error`() {
        withUserHome(tmp) {
            assumeTrue(Config.locate() == null, "a qkt.config.yaml outside the temp home is visible")
            val run = verify("verify")
            assertThat(run.code).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(run.err).contains("no qkt.config.yaml found; pass --config <path>")
        }
    }
}
