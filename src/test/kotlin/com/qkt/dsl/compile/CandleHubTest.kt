package com.qkt.dsl.compile

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class CandleHubTest {
    private val key1m = HubKey("BYBIT", "BTCUSDT", "1m")
    private val key1h = HubKey("BYBIT", "BTCUSDT", "1h")
    private val keyEth1m = HubKey("BYBIT", "ETHUSDT", "1m")

    private fun tick(
        symbol: String,
        ts: Long,
        price: String = "100",
    ): Tick = Tick(symbol = symbol, price = BigDecimal(price), timestamp = ts, volume = BigDecimal.ONE)

    @Test
    fun `register then feed accumulates closed candles in history`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("BTCUSDT", t))
        assertThat(hub.latest(key1m)).isNotNull
        assertThat(hub.history(key1m, 0)).isEqualTo(hub.latest(key1m))
    }

    @Test
    fun `register max wins when called twice`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        hub.register(key1m, retention = 20)
        assertThat(hub.retention(key1m)).isEqualTo(20)
    }

    @Test
    fun `register max retention preserved when smaller value comes second`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 50)
        hub.register(key1m, retention = 10)
        assertThat(hub.retention(key1m)).isEqualTo(50)
    }

    @Test
    fun `register after feed throws`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        hub.feed(tick("BTCUSDT", 0L))
        assertThatThrownBy { hub.register(keyEth1m, retention = 5) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `same symbol two timeframes maintain independent histories`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 100)
        hub.register(key1h, retention = 100)
        // 90 minutes of ticks at 30s cadence
        for (t in 0L..(90L * 60_000L) step 30_000L) hub.feed(tick("BTCUSDT", t))
        // 1m closes ~89 candles in this span; 1h closes ~1 candle (the 0..3_600_000 boundary at the 60min tick).
        assertThat(hub.historySize(key1m)).isGreaterThan(60)
        assertThat(hub.historySize(key1h)).isLessThanOrEqualTo(2)
    }

    @Test
    fun `feed for unrelated symbol does not affect any key`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 10)
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("ETHUSDT", t))
        assertThat(hub.historySize(key1m)).isEqualTo(0)
        assertThat(hub.latest(key1m)).isNull()
    }

    @Test
    fun `retention bounds the ring buffer`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 3)
        for (t in 0L..(10L * 60_000L) step 30_000L) hub.feed(tick("BTCUSDT", t))
        assertThat(hub.historySize(key1m)).isLessThanOrEqualTo(3)
        // Latest is the most recent close
        assertThat(hub.history(key1m, 0)).isEqualTo(hub.latest(key1m))
    }

    @Test
    fun `history out of range returns null`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("BTCUSDT", t))
        assertThat(hub.history(key1m, -1)).isNull()
        assertThat(hub.history(key1m, 9999)).isNull()
    }

    @Test
    fun `onClosed listener fires for each closed candle in registration order`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 10)
        val received = mutableListOf<Long>()
        hub.onClosed(key1m) { c -> received.add(c.endTime) }
        for (t in 0L..240_000L step 30_000L) hub.feed(tick("BTCUSDT", t))
        // Crossing 60_000 / 120_000 / 180_000 / 240_000 boundaries
        assertThat(received).isNotEmpty
        // Strictly monotonic
        assertThat(received).isSorted
    }

    @Test
    fun `onClosed for unknown key throws`() {
        val hub = CandleHub()
        assertThatThrownBy { hub.onClosed(key1m) { } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `keys returns the set of registered keys`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5)
        hub.register(key1h, retention = 5)
        hub.register(keyEth1m, retention = 5)
        assertThat(hub.keys()).containsExactlyInAnyOrder(key1m, key1h, keyEth1m)
    }
}
