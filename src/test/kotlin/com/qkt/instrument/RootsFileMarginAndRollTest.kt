package com.qkt.instrument

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class RootsFileMarginAndRollTest {
    private fun write(
        dir: Path,
        body: String,
    ): Path = dir.resolve("instruments.yaml").also { Files.writeString(it, body) }

    private fun futuresWithRoll(roll: String) =
        "futures:\n  - { root: CME:ES, currency: USD, multiplier: 50, tickSize: 0.25, " +
            "volumeStep: 1, volumeMin: 1, roll: { $roll } }\n"

    private fun option(extra: String) =
        "options:\n  - { root: DERIBIT:BTC_USDC, currency: USDC, contractSize: 1, tickSize: 5, " +
            "volumeStep: 0.01, volumeMin: 0.01, underlyingIndex: btc_usdc$extra }\n"

    @Test
    fun `a roll offset that is not a whole number is refused`(
        @TempDir dir: Path,
    ) {
        val f = write(dir, futuresWithRoll("daysBeforeExpiry: soon, atUtc: '08:00', adjust: none"))

        assertThatThrownBy { FuturesRootsFile.load(f) }
            .hasMessageContaining("roll.daysBeforeExpiry must be a whole number")
    }

    @Test
    fun `a roll time that is not a UTC time names the bad value`(
        @TempDir dir: Path,
    ) {
        val f = write(dir, futuresWithRoll("daysBeforeExpiry: 8, atUtc: 'noon', adjust: none"))

        assertThatThrownBy { FuturesRootsFile.load(f) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("roll.atUtc must be a UTC time like 08:00, got 'noon'")
    }

    @Test
    fun `an option root reads its margin map`(
        @TempDir dir: Path,
    ) {
        val f = write(dir, option(", margin: { initial: 0.15, maintenance: 0.1, basis: notional }"))

        val margin = OptionRootsFile.load(f).single().margin

        assertThat(margin?.basis).isEqualTo(MarginBasis.NOTIONAL)
        assertThat(margin?.initial).isEqualByComparingTo("0.15")
    }

    @Test
    fun `an option margin that is not a map is refused`(
        @TempDir dir: Path,
    ) {
        val f = write(dir, option(", margin: 0.15"))

        assertThatThrownBy { OptionRootsFile.load(f) }.hasMessageContaining("margin must be a map")
    }
}
