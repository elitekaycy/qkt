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

class BacktestInstrumentsTest {
    @Test
    fun `no instruments file means the standard table`(
        @TempDir dir: Path,
    ) {
        assertThat(BacktestInstruments.registry(dir, explicit = null)).isSameAs(StandardInstrumentRegistry)
    }

    @Test
    fun `a missing explicit file is a setup error`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { BacktestInstruments.registry(dir, dir.resolve("nope.yaml")) }
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
        val registry = BacktestInstruments.registry(dir, explicit = null)
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT_240927")).isNotNull
        assertThat(registry.lookup("BINANCE_UM:BTCUSDT@front")).isNotNull
        assertThat(registry.lookup("BACKTEST:XAUUSD")?.contractSize).isEqualByComparingTo("100")
    }
}
