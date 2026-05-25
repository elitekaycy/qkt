package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CandleHubSeedTest {
    private val key = HubKey("BACKTEST", "BTCUSDT", "1m")

    private fun candle(
        startMs: Long,
        close: String = "100",
    ): Candle =
        Candle(
            symbol = "BACKTEST:BTCUSDT",
            open = BigDecimal(close),
            high = BigDecimal(close),
            low = BigDecimal(close),
            close = BigDecimal(close),
            volume = BigDecimal.ONE,
            startTime = startMs,
            endTime = startMs + 60_000L,
        )

    @Test
    fun `seed on unregistered key throws`() {
        val hub = CandleHub()
        assertThatThrownBy { hub.seed(key, listOf(candle(0L))) }
            .hasMessageContaining("unknown key")
    }

    @Test
    fun `seed on empty input is a no-op`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        hub.seed(key, emptyList())
        assertThat(hub.historySize(key)).isZero
    }

    @Test
    fun `seed populates an empty ring oldest-first newest-last`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        val candles = (0..4).map { candle(it * 60_000L, close = "10$it") }
        hub.seed(key, candles)
        assertThat(hub.historySize(key)).isEqualTo(5)
        // history(0) = newest, history(N) = N-th from newest
        assertThat(hub.history(key, 0)?.startTime).isEqualTo(4 * 60_000L)
        assertThat(hub.history(key, 4)?.startTime).isEqualTo(0L)
    }

    @Test
    fun `seed sorts unordered input by startTime`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        hub.seed(key, listOf(candle(2 * 60_000L), candle(0L), candle(60_000L)))
        assertThat(hub.history(key, 0)?.startTime).isEqualTo(2 * 60_000L)
        assertThat(hub.history(key, 2)?.startTime).isEqualTo(0L)
    }

    @Test
    fun `seed truncates to retention keeping the newest bars`() {
        val hub = CandleHub()
        hub.register(key, retention = 3, strategyId = "s")
        val candles = (0..9).map { candle(it * 60_000L) }
        hub.seed(key, candles)
        assertThat(hub.historySize(key)).isEqualTo(3)
        assertThat(hub.history(key, 0)?.startTime).isEqualTo(9 * 60_000L)
        assertThat(hub.history(key, 2)?.startTime).isEqualTo(7 * 60_000L)
    }

    @Test
    fun `seed prepends candles older than the existing oldest bar`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        // Seed an initial range to simulate already-accumulated live bars.
        hub.seed(key, listOf(candle(5 * 60_000L), candle(6 * 60_000L)))
        assertThat(hub.historySize(key)).isEqualTo(2)
        // Now prepend historical bars before the existing oldest.
        hub.seed(key, listOf(candle(0L), candle(60_000L), candle(2 * 60_000L)))
        assertThat(hub.historySize(key)).isEqualTo(5)
        assertThat(hub.history(key, 0)?.startTime).isEqualTo(6 * 60_000L)
        assertThat(hub.history(key, 4)?.startTime).isEqualTo(0L)
    }

    @Test
    fun `seed drops candles overlapping or newer than the existing oldest`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        hub.seed(key, listOf(candle(5 * 60_000L), candle(6 * 60_000L)))
        // Try to seed candles that overlap or come after — should be dropped.
        hub.seed(key, listOf(candle(5 * 60_000L), candle(7 * 60_000L)))
        assertThat(hub.historySize(key)).isEqualTo(2)
        // Original oldest still at index 1 (history(1) = 5 minutes).
        assertThat(hub.history(key, 1)?.startTime).isEqualTo(5 * 60_000L)
    }

    @Test
    fun `seed does not fire onClosed callbacks`() {
        val hub = CandleHub()
        hub.register(key, retention = 10, strategyId = "s")
        val fired = mutableListOf<Candle>()
        hub.onClosed(key, "s") { fired.add(it) }
        hub.seed(key, listOf(candle(0L), candle(60_000L)))
        assertThat(fired).isEmpty()
    }
}
