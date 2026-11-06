package com.qkt.research

import com.qkt.common.Money
import com.qkt.instrument.NoopInstrumentRegistry
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ReplaySessionCommandsTest {
    private fun ticks() = (1..10).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }

    private fun write(dir: Path): Path {
        val p = dir.resolve("s.qkt")
        Files.writeString(
            p,
            """
            STRATEGY sample VERSION 1

            SYMBOLS
                btc = BACKTEST:BTCUSDT EVERY 1m

            RULES
                WHEN btc.close > 104
                THEN BUY btc SIZING 1
            """.trimIndent(),
        )
        return p
    }

    private fun session(path: Path) = ReplaySession(ticks(), path, BigDecimal("10000"), NoopInstrumentRegistry)

    @Test
    fun `a strategy that does not compile fails the session at construction`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("bad.qkt")
        Files.writeString(path, "STRATEGY broken VERSION")

        assertThatThrownBy { session(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cannot compile")
    }

    @Test
    fun `step N advances N bars when the strategy has a timeframe`(
        @TempDir dir: Path,
    ) {
        val s = session(write(dir))

        val stepped = s.dispatch(ReplayCommand.StepBars(3))

        assertThat(stepped.footer.barsClosed).isEqualTo(3L)
        assertThat(stepped.footer.exhausted).isFalse()
    }

    @Test
    fun `step by duration moves the clock forward by that much`(
        @TempDir dir: Path,
    ) {
        val s = session(write(dir))
        s.dispatch(ReplayCommand.StepBars(1))
        val start = s.dispatch(ReplayCommand.Show).footer.timestamp

        val stepped = s.dispatch(ReplayCommand.StepDuration(3 * 60_000L))

        assertThat(stepped.footer.timestamp).isGreaterThanOrEqualTo(start + 3 * 60_000L)
    }

    @Test
    fun `run-to a future time runs forward without resetting`(
        @TempDir dir: Path,
    ) {
        val s = session(write(dir))

        val ran = s.dispatch(ReplayCommand.RunToTime(5 * 60_000L))

        assertThat(ran.notice).isNull()
        assertThat(ran.footer.timestamp).isEqualTo(5 * 60_000L)
    }

    @Test
    fun `run-to next-trade stops on the first fill`(
        @TempDir dir: Path,
    ) {
        val s = session(write(dir))

        val ran = s.dispatch(ReplayCommand.RunToNextTrade)

        assertThat(ran.footer.tradeCount).isEqualTo(1)
        assertThat(ran.footer.exhausted).isFalse()
        assertThat(ran.tape).anyMatch { it is TapeEvent.Filled }
    }

    @Test
    fun `show, quit and unknown commands report without advancing`(
        @TempDir dir: Path,
    ) {
        val s = session(write(dir))

        val shown = s.dispatch(ReplayCommand.Show)
        val quit = s.dispatch(ReplayCommand.Quit)
        val unknown = s.dispatch(ReplayCommand.Unknown("jump"))

        assertThat(shown.tape).isEmpty()
        assertThat(shown.quit).isFalse()
        assertThat(quit.quit).isTrue()
        assertThat(unknown.notice).startsWith("unknown command: 'jump'")
        assertThat(unknown.footer.barsClosed).isZero()
    }
}
