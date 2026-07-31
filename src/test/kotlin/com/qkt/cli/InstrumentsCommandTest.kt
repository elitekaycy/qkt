package com.qkt.cli

import com.qkt.broker.mt5.MT5SymbolInfo
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentsCommandTest {
    @Test
    fun `verify succeeds on matching venue metadata and reports seeded mismatches`(
        @TempDir tmp: Path,
    ) {
        val config = tmp.resolve("qkt.config.yaml")
        Files.writeString(
            config,
            """
            data_root: ${tmp.toAbsolutePath()}
            brokers:
              exness:
                type: mt5
                gateway_url: http://unused
            """.trimIndent(),
        )
        Files.writeString(
            tmp.resolve("instruments.yaml"),
            """
            instruments:
              - qktSymbol: EXNESS:XAUUSD
                contractSize: 100
                volumeStep: 0.01
                volumeMin: 0.01
                volumeMax: 50
                pointSize: 0.001
                digits: 3
                tradeStopsLevelPoints: 0
            """.trimIndent(),
        )
        val output = ByteArrayOutputStream()
        val original = System.out
        try {
            System.setOut(PrintStream(output))
            val ok =
                InstrumentsCommand(
                    Args(arrayOf("instruments", "verify", "--config", config.toString())),
                ) { profile, wire ->
                    assertThat(profile.name).isEqualTo("exness")
                    assertThat(wire).isEqualTo("XAUUSDm")
                    venueInfo(contractSize = "100")
                }.run()
            assertThat(ok).isEqualTo(ExitCodes.SUCCESS)
            assertThat(output.toString()).contains("OK EXNESS:XAUUSD")

            output.reset()
            val mismatch =
                InstrumentsCommand(
                    Args(arrayOf("instruments", "verify", "--config", config.toString(), "--json")),
                ) { _, _ -> venueInfo(contractSize = "1") }
                    .run()
            assertThat(mismatch).isEqualTo(ExitCodes.USER_ERROR)
            assertThat(output.toString())
                .contains("\"ok\":false")
                .contains("\"field\":\"contractSize\"")
                .contains("\"expected\":\"100\"")
                .contains("\"actual\":\"1\"")
        } finally {
            System.setOut(original)
        }
    }

    private fun venueInfo(contractSize: String) =
        MT5SymbolInfo(
            ask = BigDecimal("2000.01"),
            bid = BigDecimal("2000.00"),
            digits = 3,
            point = BigDecimal("0.001"),
            tradeStopsLevel = 0,
            volumeMin = BigDecimal("0.01"),
            volumeStep = BigDecimal("0.01"),
            contractSize = BigDecimal(contractSize),
            volumeMax = BigDecimal("50"),
        )
}
