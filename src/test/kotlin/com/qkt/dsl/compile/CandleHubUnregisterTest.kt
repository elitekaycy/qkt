package com.qkt.dsl.compile

import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class CandleHubUnregisterTest {
    private val key = HubKey("BACKTEST", "BTCUSDT", "1m")

    private fun tick(
        sym: String,
        ts: Long,
    ): Tick = Tick(sym, BigDecimal("100"), ts)

    @Test
    fun `unregister removes a strategy's listeners but leaves others alive`() {
        val hub = CandleHub()
        hub.register(key, retention = 5, strategyId = "alpha")
        hub.register(key, retention = 5, strategyId = "beta")

        val alphaSeen = mutableListOf<Long>()
        val betaSeen = mutableListOf<Long>()
        hub.onClosed(key, "alpha") { c -> alphaSeen.add(c.endTime) }
        hub.onClosed(key, "beta") { c -> betaSeen.add(c.endTime) }

        hub.unregister("alpha")

        val base = 1_705_276_800_000L
        hub.feed(tick("BACKTEST:BTCUSDT", base))
        hub.feed(tick("BACKTEST:BTCUSDT", base + 30_000))
        hub.feed(tick("BACKTEST:BTCUSDT", base + 90_000))

        assertThat(alphaSeen).isEmpty()
        assertThat(betaSeen).isNotEmpty
    }

    @Test
    fun `unregister drops the slot entirely when no owner remains`() {
        val hub = CandleHub()
        hub.register(key, retention = 5, strategyId = "alpha")
        assertThat(hub.keys()).contains(key)

        hub.unregister("alpha")

        assertThat(hub.keys()).doesNotContain(key)
    }

    @Test
    fun `unregister keeps the slot when other owners still need it`() {
        val hub = CandleHub()
        hub.register(key, retention = 5, strategyId = "alpha")
        hub.register(key, retention = 5, strategyId = "beta")

        hub.unregister("alpha")

        assertThat(hub.keys()).contains(key)
    }

    @Test
    fun `unregister with unknown strategyId is a no-op`() {
        val hub = CandleHub()
        hub.register(key, retention = 5, strategyId = "alpha")

        hub.unregister("ghost")

        assertThat(hub.keys()).contains(key)
    }

    @Test
    fun `re-registering after unregister produces a fresh slot`() {
        val hub = CandleHub()
        hub.register(key, retention = 3, strategyId = "alpha")
        hub.unregister("alpha")

        hub.register(key, retention = 7, strategyId = "alpha")
        assertThat(hub.retention(key)).isEqualTo(7)
        assertThat(hub.historySize(key)).isEqualTo(0)
    }
}
