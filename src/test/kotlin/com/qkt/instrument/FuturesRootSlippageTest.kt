package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FuturesRootSlippageTest {
    private fun load(
        dir: Path,
        slippage: String,
    ): List<FuturesRoot> {
        val file = dir.resolve("instruments.yaml")
        Files.writeString(
            file,
            "futures:\n  - { root: BINANCE_UM:BTCUSDT, currency: USDT, multiplier: 1, tickSize: 0.1, " +
                "volumeStep: 0.001, volumeMin: 0.001$slippage }\n",
        )
        return FuturesRootsFile.load(file)
    }

    @Test
    fun `slippage ticks become every contract's slippage points`(
        @TempDir dir: Path,
    ) {
        val root = load(dir, ", slippageTicks: 3").single()

        assertThat(root.slippageTicks).isEqualTo(3)
        assertThat(root.metaFor("BINANCE_UM:BTCUSDT_240927", 1L).slippagePoints).isEqualTo(3)
    }

    @Test
    fun `slippage ticks default to zero`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, "").single().metaFor("BINANCE_UM:BTCUSDT@front", null).slippagePoints).isZero()
    }

    @Test
    fun `negative slippage ticks name the root`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { load(dir, ", slippageTicks: -1") }
            .hasMessageContaining("BINANCE_UM:BTCUSDT")
            .hasMessageContaining("slippageTicks")
    }

    @Test
    fun `the expiry guard defaults to a day and is read from the root`(
        @TempDir dir: Path,
    ) {
        assertThat(load(dir, "").single().expiryGuardHours).isEqualTo(24)
        assertThat(load(dir, ", expiryGuardHours: 6").single().metaFor("BINANCE_UM:BTCUSDT_240927", 1L).derivative)
            .isEqualTo(FutureTerms("BINANCE_UM:BTCUSDT", 1L, expiryGuardHours = 6))
    }
}
