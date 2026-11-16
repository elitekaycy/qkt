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

/** Without a declared stream there is no bar to count, so a step counts ticks. */
class ReplaySessionTickStepTest {
    @Test
    fun `step N advances N ticks when the strategy declares no stream`(
        @TempDir dir: Path,
    ) {
        val path = dir.resolve("s.qkt").also { Files.writeString(it, "STRATEGY bare VERSION 1\nLET x = 1\n") }
        val ticks = (1..10).map { Tick("BACKTEST:BTCUSDT", Money.of((100 + it).toString()), it * 60_000L) }
        val session = ReplaySession(ticks, path, BigDecimal("10000"), NoopInstrumentRegistry)

        val stepped = session.dispatch(ReplayCommand.StepBars(3))

        assertThat(stepped.footer.timestamp).isEqualTo(3 * 60_000L)
        assertThat(stepped.footer.barsClosed).isZero()
        assertThat(stepped.footer.exhausted).isFalse()
    }
}
