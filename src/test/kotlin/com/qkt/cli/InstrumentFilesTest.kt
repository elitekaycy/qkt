package com.qkt.cli

import com.qkt.instrument.ContractCatalog
import com.qkt.instrument.ContractCatalogStore
import com.qkt.instrument.ListedContract
import com.qkt.instrument.StandardInstrumentRegistry
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentFilesTest {
    @Test
    fun `no instruments file means the standard table`(
        @TempDir dir: Path,
    ) {
        assertThat(InstrumentFiles.registry(dir, explicit = null)).isSameAs(StandardInstrumentRegistry)
    }

    @Test
    fun `a missing explicit file is a setup error`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { InstrumentFiles.registry(dir, dir.resolve("nope.yaml")) }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("nope.yaml")
    }

    @Test
    fun `futures roots and catalogs resolve beside yaml and standard entries`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            """
            instruments: []
            futures:
              - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, volumeStep: 0.001, volumeMin: 0.001 }
            """.trimIndent(),
        )
        ContractCatalogStore(
            dir,
        ).write(ContractCatalog("BINANCE_UM:BTCUSDT", listOf(ListedContract("BTCUSDT_240927", 1L))))
        val registry = InstrumentFiles.registry(dir, explicit = null)
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT_240927")).isNotNull
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT@front")).isNotNull
        assertThat(registry.lookup("BACKTEST:XAUUSD")?.contractSize).isEqualByComparingTo("100")
    }

    @Test
    fun `commission-per-lot overrides every symbol rate`(
        @TempDir dir: Path,
    ) {
        val registry =
            InstrumentFiles.forBacktest(
                dir,
                Args(arrayOf("backtest", "s.qkt", "--commission-per-lot", "7")),
                listOf("BACKTEST:XAUUSD"),
                java.time.Instant.parse("2024-01-15T00:00:00Z"),
                java.time.Instant.parse("2024-01-16T00:00:00Z"),
            )
        assertThat(registry.lookup("BACKTEST:XAUUSD")?.commissionPerLot).isEqualByComparingTo("7")
    }

    @Test
    fun `negative commission-per-lot is a setup error`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy {
            InstrumentFiles.forBacktest(
                dir,
                Args(arrayOf("backtest", "s.qkt", "--commission-per-lot", "-1")),
                listOf("BACKTEST:XAUUSD"),
                java.time.Instant.parse("2024-01-15T00:00:00Z"),
                java.time.Instant.parse("2024-01-16T00:00:00Z"),
            )
        }.isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessageContaining("--commission-per-lot")
    }

    @Test
    fun `an option missing from its root's catalog fails before the run`(
        @TempDir dir: Path,
    ) {
        Files.writeString(
            dir.resolve("instruments.yaml"),
            "options:\n  - { root: DERIBIT:BTC_USDC, currency: USDC, contractSize: 1, tickSize: 5, volumeStep: 0.01, " +
                "volumeMin: 0.01, underlyingIndex: btc_usdc }\n",
        )

        assertThatThrownBy { InstrumentFiles.registry(dir, null, listOf("DERIBIT:BTC_USDC_27SEP24_60000_C")) }
            .hasMessageContaining("qkt fetch DERIBIT:BTC_USDC --catalog")
    }
}
