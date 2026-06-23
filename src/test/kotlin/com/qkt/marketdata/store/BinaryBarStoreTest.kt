package com.qkt.marketdata.store

import com.qkt.candles.TimeWindow
import com.qkt.common.Money
import com.qkt.marketdata.Candle
import java.math.BigDecimal
import java.nio.file.Path
import java.time.LocalDate
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BinaryBarStoreTest {
    private fun bd(s: String) = BigDecimal(s).setScale(Money.SCALE, Money.ROUNDING)

    @Test
    fun `write then read a day and presence is coverage`(
        @TempDir dir: Path,
    ) {
        val store = BinaryBarStore(dir)
        val tf = TimeWindow.parse("15m")
        val day = LocalDate.parse("2024-01-04")
        val base = 1_712_000_000_000L
        val bars =
            listOf(
                Candle("XAUUSD", bd("1850"), bd("1851"), bd("1849"), bd("1850.5"), bd("10"), base, base + 900_000L),
                Candle(
                    "XAUUSD",
                    bd("1850.5"),
                    bd("1852"),
                    bd("1850"),
                    bd("1851.2"),
                    bd("7"),
                    base + 900_000L,
                    base + 1_800_000L,
                ),
            )

        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isFalse()
        store.writeDay("BACKTEST", "XAUUSD", tf, day, bars)
        assertThat(store.hasDay("BACKTEST", "XAUUSD", tf, day)).isTrue()
        // Read-back carries the prefixed qktSymbol the engine routes by; OHLCV/timestamps preserved.
        assertThat(store.readDay("BACKTEST", "XAUUSD", tf, day))
            .isEqualTo(bars.map { it.copy(symbol = "BACKTEST:XAUUSD") })
    }

    @Test
    fun `builtTimeframes lists the tfs with bars for a symbol`(
        @TempDir dir: Path,
    ) {
        val store = BinaryBarStore(dir)
        val day = LocalDate.parse("2024-01-04")
        val base = 1_712_000_000_000L

        fun bar(tf: TimeWindow) =
            listOf(Candle("EURUSD", bd("1.1"), bd("1.2"), bd("1.0"), bd("1.15"), bd("3"), base, base + tf.durationMs))

        assertThat(store.builtTimeframes("BACKTEST", "EURUSD")).isEmpty()
        store.writeDay("BACKTEST", "EURUSD", TimeWindow.parse("1m"), day, bar(TimeWindow.parse("1m")))
        store.writeDay("BACKTEST", "EURUSD", TimeWindow.parse("30m"), day, bar(TimeWindow.parse("30m")))
        // Another symbol's tf must not leak in.
        store.writeDay("BACKTEST", "GBPUSD", TimeWindow.parse("1h"), day, bar(TimeWindow.parse("1h")))

        assertThat(store.builtTimeframes("BACKTEST", "EURUSD"))
            .containsExactlyInAnyOrder(TimeWindow.parse("1m"), TimeWindow.parse("30m"))
    }
}
