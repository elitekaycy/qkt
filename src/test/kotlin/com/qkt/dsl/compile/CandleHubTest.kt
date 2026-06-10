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
        hub.register(key1m, retention = 5, strategyId = "test")
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("BYBIT:BTCUSDT", t))
        assertThat(hub.latest(key1m)).isNotNull
        assertThat(hub.history(key1m, 0)).isEqualTo(hub.latest(key1m))
    }

    @Test
    fun `register max wins when called twice`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5, strategyId = "test")
        hub.register(key1m, retention = 20, strategyId = "test")
        assertThat(hub.retention(key1m)).isEqualTo(20)
    }

    @Test
    fun `register max retention preserved when smaller value comes second`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 50, strategyId = "test")
        hub.register(key1m, retention = 10, strategyId = "test")
        assertThat(hub.retention(key1m)).isEqualTo(50)
    }

    @Test
    fun `register after feed adds a new key`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5, strategyId = "test")
        hub.feed(tick("BYBIT:BTCUSDT", 0L))
        // Daemon scope: late-registering a new key (e.g. when a second strategy deploys after
        // ticks have started flowing) is allowed. The new key simply has no prior history.
        hub.register(keyEth1m, retention = 5, strategyId = "test")
        assertThat(hub.keys()).contains(key1m, keyEth1m)
    }

    @Test
    fun `same symbol two timeframes maintain independent histories`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 100, strategyId = "test")
        hub.register(key1h, retention = 100, strategyId = "test")
        // 90 minutes of ticks at 30s cadence
        for (t in 0L..(90L * 60_000L) step 30_000L) hub.feed(tick("BYBIT:BTCUSDT", t))
        // 1m closes ~89 candles in this span; 1h closes ~1 candle (the 0..3_600_000 boundary at the 60min tick).
        assertThat(hub.historySize(key1m)).isGreaterThan(60)
        assertThat(hub.historySize(key1h)).isLessThanOrEqualTo(2)
    }

    @Test
    fun `feed for unrelated symbol does not affect any key`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 10, strategyId = "test")
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("BYBIT:ETHUSDT", t))
        assertThat(hub.historySize(key1m)).isEqualTo(0)
        assertThat(hub.latest(key1m)).isNull()
    }

    @Test
    fun `retention bounds the ring buffer`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 3, strategyId = "test")
        for (t in 0L..(10L * 60_000L) step 30_000L) hub.feed(tick("BYBIT:BTCUSDT", t))
        assertThat(hub.historySize(key1m)).isLessThanOrEqualTo(3)
        // Latest is the most recent close
        assertThat(hub.history(key1m, 0)).isEqualTo(hub.latest(key1m))
    }

    @Test
    fun `history out of range returns null`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5, strategyId = "test")
        for (t in 0L..180_000L step 30_000L) hub.feed(tick("BYBIT:BTCUSDT", t))
        assertThat(hub.history(key1m, -1)).isNull()
        assertThat(hub.history(key1m, 9999)).isNull()
    }

    @Test
    fun `onClosed listener fires for each closed candle in registration order`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 10, strategyId = "test")
        val received = mutableListOf<Long>()
        hub.onClosed(key1m, "test") { c -> received.add(c.endTime) }
        for (t in 0L..240_000L step 30_000L) hub.feed(tick("BYBIT:BTCUSDT", t))
        // Crossing 60_000 / 120_000 / 180_000 / 240_000 boundaries
        assertThat(received).isNotEmpty
        // Strictly monotonic
        assertThat(received).isSorted
    }

    @Test
    fun `onClosed for unknown key throws`() {
        val hub = CandleHub()
        assertThatThrownBy { hub.onClosed(key1m, "test") { } }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `keys returns the set of registered keys`() {
        val hub = CandleHub()
        hub.register(key1m, retention = 5, strategyId = "test")
        hub.register(key1h, retention = 5, strategyId = "test")
        hub.register(keyEth1m, retention = 5, strategyId = "test")
        assertThat(hub.keys()).containsExactlyInAnyOrder(key1m, key1h, keyEth1m)
    }

    @Test
    fun `flushClosed finishes a quiet symbol's bar without a next tick`() {
        val hub = CandleHub()
        val key = HubKey("EXNESS", "XAUUSD", "1m")
        hub.register(key, retention = 5, strategyId = "t")
        val closed = mutableListOf<com.qkt.marketdata.Candle>()
        hub.onClosed(key, "t") { closed.add(it) }

        hub.feed(com.qkt.marketdata.Tick("EXNESS:XAUUSD", java.math.BigDecimal("2000"), 10_000L))
        assertThat(closed).isEmpty()

        // Window [0, 60s) ended; no further tick ever arrives. The heartbeat flush
        // must close it — otherwise the last bar of a quiet session never evaluates.
        hub.flushClosed(59_999L)
        assertThat(closed).isEmpty()
        hub.flushClosed(60_000L)
        assertThat(closed).hasSize(1)
        assertThat(closed.single().close).isEqualByComparingTo("2000")
        // Idempotent: nothing left to flush.
        hub.flushClosed(120_000L)
        assertThat(closed).hasSize(1)
    }
}
