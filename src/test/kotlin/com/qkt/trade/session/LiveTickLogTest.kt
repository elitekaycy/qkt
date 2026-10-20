package com.qkt.trade.session

import com.qkt.marketdata.Tick
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A live session keeps every tick it traded on, so its replay runs over the same market. */
class LiveTickLogTest {
    @Test
    fun `the log reads back the ticks it was given`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("ticks.csv")
        val ticks =
            listOf(
                Tick(
                    "EXNESS:XAUUSD",
                    BigDecimal("2400.5"),
                    1_000L,
                    bid = BigDecimal("2400.4"),
                    ask = BigDecimal("2400.6"),
                ),
                Tick("DERIBIT:BTC-PERPETUAL", BigDecimal("67000"), 2_000L, volume = BigDecimal("3")),
            )

        LiveTickLog(path).use { log -> ticks.forEach(log::append) }

        assertThat(LiveTickLog.read(path)).isEqualTo(ticks)
    }

    @Test
    fun `the session recorder hands every live tick to the log`(
        @TempDir tmp: Path,
    ) {
        val path = tmp.resolve("ticks.csv")
        val tick = Tick("EXNESS:XAUUSD", BigDecimal("2400.5"), 1_000L)

        LiveTickLog(path).use { log ->
            BotSessionRecorder(BarHistory(10), log::append).onTick(tick, testStrategyContext()) {}
        }

        assertThat(LiveTickLog.read(path)).containsExactly(tick)
    }
}
