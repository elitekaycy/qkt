package com.qkt.observe.insights

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class MarketDataInsightsTest {
    @Test
    fun `a recovered symbol ships the episode length beside the usual market-data fields`() {
        val env =
            InsightsTranslate.marketDataRecovered(
                source = "mt5",
                symbol = "EXNESS:XAUUSD",
                ts = 1718000000500L,
                reason = "fresh tick after stale",
                unhealthyForMs = 184_000L,
            )

        assertThat(env.type).isEqualTo("marketdata.recovered")
        assertThat(env.id).isEqualTo("marketdata-recovered-mt5-1718000000500")
        assertThat(env.strategyId).isNull()
        assertThat(env.payload).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "source" to "mt5",
                "symbols" to listOf("EXNESS:XAUUSD"),
                "state" to "recovered",
                "reason" to "fresh tick after stale",
                "ts" to 1718000000500L,
                "unhealthyForMs" to 184_000L,
            ),
        )
        assertThat(env.toJson("i1")).contains(
            "\"payload\":{\"source\":\"mt5\",\"symbols\":[\"EXNESS:XAUUSD\"],\"state\":\"recovered\"," +
                "\"reason\":\"fresh tick after stale\",\"ts\":1718000000500,\"unhealthyForMs\":184000}",
        )
    }

    @Test
    fun `a stale event names its fault kind and keeps the reason text`() {
        val env =
            InsightsTranslate.marketDataStale(
                source = "mt5",
                symbol = "EXNESS:XAUUSD",
                ts = 1718000000400L,
                reason = "broker tick clock skew -61441ms exceeds 60000ms",
                kind = "clock_skew",
            )

        assertThat(env.type).isEqualTo("marketdata.stale")
        assertThat(env.id).isEqualTo("marketdata-stale-mt5-1718000000400")
        assertThat(env.payload)
            .containsEntry("state", "stale")
            .containsEntry("reason", "broker tick clock skew -61441ms exceeds 60000ms")
            .containsEntry("kind", "clock_skew")
            .containsEntry("symbols", listOf("EXNESS:XAUUSD"))
            .containsEntry("ts", 1718000000400L)
    }

    @Test
    fun `a stale event without a kind omits the field`() {
        val env = InsightsTranslate.marketDataStale("mt5", "EXNESS:XAUUSD", 1718000000400L, "quote age")

        assertThat(env.payload).doesNotContainKey("kind")
    }
}
