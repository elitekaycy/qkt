package com.qkt.research

import com.qkt.common.Money
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplaySessionTest {
    private fun ticks() = (1..10).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }

    private fun writeStrategy(
        dir: Path,
        threshold: String,
    ): Path {
        val p = dir.resolve("s.qkt")
        Files.writeString(
            p,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > $threshold
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        return p
    }

    private fun session(path: Path) =
        ReplaySession(
            ticks = ticks(),
            strategyPath = path,
            startingBalance = BigDecimal("10000"),
            instruments = NoopInstrumentRegistry,
        )

    @Test
    fun `run advances to end and reset returns to the start`(
        @TempDir dir: Path,
    ) {
        val s = session(writeStrategy(dir, "100"))
        val ran = s.dispatch(ReplayCommand.Run)
        assertThat(ran.footer.exhausted).isTrue()

        val reset = s.dispatch(ReplayCommand.Reset)
        assertThat(reset.footer.exhausted).isFalse()
        assertThat(reset.footer.barsClosed).isZero()
        assertThat(reset.footer.tradeCount).isZero()
    }

    @Test
    fun `reload picks up the edited strategy`(
        @TempDir dir: Path,
    ) {
        val path = writeStrategy(dir, "100") // close (>100) always true -> trades
        val s = session(path)
        val before = s.dispatch(ReplayCommand.Run)
        assertThat(before.footer.tradeCount).isGreaterThan(0)

        Files.writeString(
            path,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 100000
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        val reloaded = s.dispatch(ReplayCommand.Reload)
        assertThat(reloaded.reloadErrors).isEmpty()
        val after = s.dispatch(ReplayCommand.Run)
        assertThat(after.footer.tradeCount).isZero()
    }

    @Test
    fun `reload surfaces parse errors and keeps the old strategy`(
        @TempDir dir: Path,
    ) {
        val path = writeStrategy(dir, "100")
        val s = session(path)
        Files.writeString(path, "STRATEGY broken VERSION") // truncated -> parse error
        val reloaded = s.dispatch(ReplayCommand.Reload)
        assertThat(reloaded.reloadErrors).isNotEmpty()

        // old strategy still works
        val after = s.dispatch(ReplayCommand.Run)
        assertThat(after.footer.tradeCount).isGreaterThan(0)
    }

    @Test
    fun `run-to a past time resets and runs forward`(
        @TempDir dir: Path,
    ) {
        val s = session(writeStrategy(dir, "100"))
        s.dispatch(ReplayCommand.Run) // at end
        val back = s.dispatch(ReplayCommand.RunToTime(3 * 60_000L))
        assertThat(back.notice).contains("reset")
        assertThat(back.footer.timestamp).isEqualTo(3 * 60_000L)
        assertThat(back.footer.exhausted).isFalse()
    }
}
