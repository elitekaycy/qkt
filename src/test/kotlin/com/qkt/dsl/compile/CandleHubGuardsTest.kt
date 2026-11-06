package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [CandleHub] history lookups before the requested time, seeding checks and sync-group registration checks. */
class CandleHubGuardsTest {
    private val gold = HubKey("BACKTEST", "XAUUSD", "1m")
    private val silver = HubKey("BACKTEST", "XAGUSD", "1m")
    private val one = BigDecimal.ONE

    private fun bar(
        symbol: String,
        endTime: Long,
    ) = Candle(symbol, one, one, one, one, BigDecimal.ZERO, endTime - 60_000L, endTime)

    @Test
    fun `no bar is returned for a time before the oldest bar`() {
        val hub = CandleHub()
        hub.register(gold, retention = 3, strategyId = "s")
        hub.publish(gold, bar(gold.qktSymbol, 120_000L))

        assertThat(hub.latestAtOrBefore(gold, 60_000L)).isNull()
        assertThat(hub.latestAtOrBefore(gold, 120_000L)?.endTime).isEqualTo(120_000L)
    }

    @Test
    fun `seeding history of another symbol is refused`() {
        val hub = CandleHub()
        hub.register(gold, retention = 3, strategyId = "s")

        assertThatThrownBy { hub.seed(gold, listOf(bar(silver.qktSymbol, 60_000L))) }
            .hasMessageContaining("CandleHub.seed: symbol mismatch for $gold")
    }

    @Test
    fun `a sync group over an unregistered stream is refused`() {
        val hub = CandleHub()
        hub.register(gold, retention = 3, strategyId = "s")

        assertThatThrownBy { hub.registerSyncGroup(SyncGroupKey(mapOf("g" to gold, "s" to silver), null), "s") }
            .hasMessageContaining("registerSyncGroup: alias 's' refers to unregistered stream $silver")
    }

    @Test
    fun `reads on a stream the hub never registered are empty`() {
        val hub = CandleHub()

        assertThat(hub.latest(gold)).isNull()
        assertThat(hub.history(gold, 1)).isNull()
        assertThat(hub.retention(gold)).isZero
        assertThat(hub.historySize(gold)).isZero
        assertThat(hub.seededHistory(gold)).isEmpty()
        assertThat(hub.latestAtOrBefore(gold, 0L)).isNull()
        assertThatThrownBy { hub.publish(gold, bar(gold.qktSymbol, 60_000L)) }
            .hasMessageContaining("CandleHub.publish: unknown key $gold")
    }

    @Test
    fun `registration needs a positive retention and a strategy id`() {
        assertThatThrownBy {
            CandleHub().register(gold, retention = 0, strategyId = "s")
        }.hasMessageContaining("retention must be >= 1: 0")
        assertThatThrownBy {
            CandleHub().register(gold, retention = 1, strategyId = " ")
        }.hasMessageContaining("strategyId must be non-blank")
    }
}
