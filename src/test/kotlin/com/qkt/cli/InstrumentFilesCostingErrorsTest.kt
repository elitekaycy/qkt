package com.qkt.cli

import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class InstrumentFilesCostingErrorsTest {
    private val from = Instant.parse("2024-01-15T00:00:00Z")
    private val to = Instant.parse("2024-01-16T00:00:00Z")

    private fun forBacktest(
        dir: Path,
        symbols: List<String>,
        vararg flags: String,
    ) = InstrumentFiles.forBacktest(dir, Args(arrayOf("backtest", "s.qkt", *flags)), symbols, from, to)

    @Test
    fun `funding is on or off and nothing else`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { forBacktest(dir, listOf("BACKTEST:XAUUSD"), "--funding", "maybe") }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("--funding must be on or off, not 'maybe'")
    }

    @Test
    fun `a costing flag that is not a number is a setup error`(
        @TempDir dir: Path,
    ) {
        assertThatThrownBy { forBacktest(dir, listOf("BACKTEST:XAUUSD"), "--swap-scale", "lots") }
            .isInstanceOf(BacktestContext.Companion.SetupError::class.java)
            .hasMessage("bad --swap-scale 'lots': expected a non-negative financing multiplier")
    }

    @Test
    fun `a costing override on a symbol with no instrument metadata is warned about`(
        @TempDir dir: Path,
    ) {
        val run =
            captureCliRun { forBacktest(dir, listOf("EXNESS:NOSUCHSYMBOL"), "--commission-per-lot", "7").let { 0 } }

        assertThat(run.err)
            .contains("qkt: WARNING — --commission-per-lot do not apply to EXNESS:NOSUCHSYMBOL: no instrument metadata")
    }
}
