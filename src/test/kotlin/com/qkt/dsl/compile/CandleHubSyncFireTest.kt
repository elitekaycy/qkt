package com.qkt.dsl.compile

import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * #45 — Phase 35 Task 5. Atomic-fire behaviour of [CandleHub] sync groups:
 *
 *  - One callback per window-end, after every member has closed a bar there.
 *  - Order-independent: A-then-B and B-then-A both fire on the second arrival.
 *  - Independent windows pending simultaneously (later window for one member,
 *    earlier still waiting on the other).
 *  - Per-stream `onClosed` listeners still fire as usual; sync is additive.
 *  - Tick-driven timeout drops a stale window with no fire and no leak.
 *  - More than two members supported.
 */
class CandleHubSyncFireTest {
    private val gold = HubKey("EXNESS", "XAUUSD", "1m")
    private val silver = HubKey("EXNESS", "XAGUSD", "1m")
    private val platinum = HubKey("EXNESS", "XPTUSD", "1m")

    private fun tick(
        symbol: String,
        ts: Long,
        price: String = "100",
    ): Tick =
        Tick(
            symbol = symbol,
            price = BigDecimal(price),
            timestamp = ts,
            volume = BigDecimal.ONE,
        )

    private fun hubWithGoldSilver(
        strategyId: String = "alpha",
        timeoutMs: Long? = null,
    ): Pair<CandleHub, SyncGroupKey> {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = strategyId)
        hub.register(silver, retention = 5, strategyId = strategyId)
        val group =
            SyncGroupKey(
                members = mapOf("gold" to gold, "silver" to silver),
                timeoutMs = timeoutMs,
            )
        hub.registerSyncGroup(group, strategyId)
        return hub to group
    }

    @Test
    fun `fires once when both members close the same window`() {
        val (hub, group) = hubWithGoldSilver()
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        // Drive both streams through the same 1m window 0..60_000ms.
        // CandleAggregator closes a bar when a tick lands beyond its end.
        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAGUSD", 0, "25"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001"))
        hub.feed(tick("EXNESS:XAGUSD", 60_000, "26"))

        assertThat(fires).hasSize(1)
        assertThat(fires[0].keys).containsExactlyInAnyOrder("gold", "silver")
        assertThat(fires[0]["gold"]?.endTime).isEqualTo(fires[0]["silver"]?.endTime)
    }

    @Test
    fun `fires in reverse arrival order silver then gold`() {
        val (hub, group) = hubWithGoldSilver()
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        hub.feed(tick("EXNESS:XAGUSD", 0, "25"))
        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAGUSD", 60_000, "26"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001"))

        assertThat(fires).hasSize(1)
        assertThat(fires[0]["gold"]).isNotNull
        assertThat(fires[0]["silver"]).isNotNull
    }

    @Test
    fun `does not fire until every member has closed the window`() {
        val (hub, group) = hubWithGoldSilver()
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        // Only gold closes a bar — silver never advances past the same window.
        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001"))

        assertThat(fires).isEmpty()
    }

    @Test
    fun `fires once per window when many windows close in sequence`() {
        val (hub, group) = hubWithGoldSilver()
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        for (t in 0L..180_000L step 60_000L) {
            hub.feed(tick("EXNESS:XAUUSD", t, "1000"))
            hub.feed(tick("EXNESS:XAGUSD", t, "25"))
        }
        // Three closes expected: windows ending at 60_000, 120_000, 180_000.
        assertThat(fires).hasSize(3)
        assertThat(fires.map { it["gold"]?.endTime }).containsExactly(60_000L, 120_000L, 180_000L)
    }

    @Test
    fun `regular onClosed listeners still fire alongside sync`() {
        val (hub, group) = hubWithGoldSilver()
        val syncFires = mutableListOf<Map<String, Candle>>()
        val goldFires = mutableListOf<Candle>()
        hub.onSyncClosed(group, "alpha") { bars -> syncFires.add(bars) }
        hub.onClosed(gold, "alpha") { c -> goldFires.add(c) }

        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAGUSD", 0, "25"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001"))
        hub.feed(tick("EXNESS:XAGUSD", 60_000, "26"))

        assertThat(syncFires).hasSize(1)
        assertThat(goldFires).hasSize(1)
    }

    @Test
    fun `window times out and never fires when timeoutMs elapses`() {
        val (hub, group) = hubWithGoldSilver(timeoutMs = 500L)
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        // Gold closes window [0..60_000). Tick arriving past timeout means silver is dead.
        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001")) // closes gold's bar at endTime=60_000

        // Silver's tick lands but never crosses the 60_000 boundary, and the gold heartbeat
        // is past timeoutMs from the gold-close instant.
        hub.feed(tick("EXNESS:XAUUSD", 60_600, "1002")) // tick.timestamp > goldFirstSeen + 500ms

        assertThat(fires).isEmpty()

        // Silver finally closes the same window — must NOT retro-fire because pending was dropped.
        hub.feed(tick("EXNESS:XAGUSD", 0, "25"))
        hub.feed(tick("EXNESS:XAGUSD", 60_000, "26"))
        assertThat(fires).isEmpty()
    }

    @Test
    fun `three-member group fires only when all three close the same window`() {
        val hub = CandleHub()
        hub.register(gold, retention = 5, strategyId = "alpha")
        hub.register(silver, retention = 5, strategyId = "alpha")
        hub.register(platinum, retention = 5, strategyId = "alpha")
        val group =
            SyncGroupKey(
                members = mapOf("gold" to gold, "silver" to silver, "platinum" to platinum),
                timeoutMs = null,
            )
        hub.registerSyncGroup(group, "alpha")
        val fires = mutableListOf<Map<String, Candle>>()
        hub.onSyncClosed(group, "alpha") { bars -> fires.add(bars) }

        hub.feed(tick("EXNESS:XAUUSD", 0, "1000"))
        hub.feed(tick("EXNESS:XAGUSD", 0, "25"))
        hub.feed(tick("EXNESS:XAUUSD", 60_000, "1001"))
        hub.feed(tick("EXNESS:XAGUSD", 60_000, "26"))
        assertThat(fires).isEmpty() // platinum hasn't closed yet

        hub.feed(tick("EXNESS:XPTUSD", 0, "900"))
        hub.feed(tick("EXNESS:XPTUSD", 60_000, "901"))

        assertThat(fires).hasSize(1)
        assertThat(fires[0].keys).containsExactlyInAnyOrder("gold", "silver", "platinum")
    }
}
