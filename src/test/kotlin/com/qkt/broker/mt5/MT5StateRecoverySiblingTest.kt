package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.FixedClock
import com.qkt.common.MonotonicSequenceGenerator
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * End-to-end recovery wiring for #154. The pure-function logic is covered by
 * [MT5StateRecoveryMatchTest]; these tests confirm that [MT5StateRecovery.recover]
 * actually consults `siblingsLookup` and translates [OrphanMatch.ConflictWithSibling]
 * into a refusal to seed.
 */
class MT5StateRecoverySiblingTest {
    private lateinit var server: MockWebServer
    private lateinit var client: MT5Client
    private lateinit var profile: MT5BrokerProfile

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client =
            MT5Client(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
                serverTimeZone = MT5ServerTimeZone.UTC,
                httpTimeoutMs = 2000,
                retryAttempts = 0,
            )
        profile =
            MT5DefaultProfiles.exness.copy(
                gatewayUrl = server.url("/").toString().trimEnd('/'),
            )
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `seeds orphan when no sibling shares the truncated prefix`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":42,"symbol":"XAUUSDm","type":0,"volume":"0.10","price_open":"4500.0",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-hedge-stradd"}]""",
            ),
        )
        val seeded = mutableListOf<Triple<Long, String, String>>()
        val recovery =
            MT5StateRecovery(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator()),
                strategyName = "hedge-straddle",
                seedOrphan = { ticket, orderId, strategyId -> seeded.add(Triple(ticket, orderId, strategyId)) },
                siblingsLookup = { listOf("pairs-xau-xag") }, // disjoint prefix
            )
        recovery.recover()
        assertThat(seeded).hasSize(1)
        assertThat(seeded.first().third).isEqualTo("hedge-straddle")
    }

    @Test
    fun `refuses to seed when a sibling could also claim the truncated comment (#154)`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":42,"symbol":"XAUUSDm","type":0,"volume":"0.10","price_open":"4500.0",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-hedge_straddl"}]""",
            ),
        )
        val seeded = mutableListOf<Triple<Long, String, String>>()
        val recovery =
            MT5StateRecovery(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator()),
                strategyName = "hedge_straddle_a",
                seedOrphan = { ticket, orderId, strategyId -> seeded.add(Triple(ticket, orderId, strategyId)) },
                siblingsLookup = { listOf("hedge_straddle_b") },
            )
        recovery.recover()
        assertThat(seeded).isEmpty()
    }

    @Test
    fun `siblings lookup is consulted at recovery time, not construction time`() {
        // Simulates a strategy deployed AFTER the broker was built — the lookup must
        // see the sibling appear in the registry between construction and recover().
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":42,"symbol":"XAUUSDm","type":0,"volume":"0.10","price_open":"4500.0",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-hedge_straddl"}]""",
            ),
        )
        val siblings = mutableListOf<String>()
        val seeded = mutableListOf<Long>()
        val recovery =
            MT5StateRecovery(
                client = client,
                profile = profile,
                symbol = MT5Symbol(profile.symbolPolicy),
                bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator()),
                strategyName = "hedge_straddle_a",
                seedOrphan = { ticket, _, _ -> seeded.add(ticket) },
                siblingsLookup = { siblings.toList() },
            )
        siblings.add("hedge_straddle_b") // arrives after construction
        recovery.recover()
        assertThat(seeded).isEmpty()
    }

    @Test
    fun `startup PositionReconciled carries the profile-prefixed qkt symbol (#1103)`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":42,"symbol":"XAUUSDm","type":1,"volume":"0.10","price_open":"4500.0",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-hedge-stradd"}]""",
            ),
        )
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val seen = mutableListOf<com.qkt.events.BrokerEvent.PositionReconciled>()
        bus.subscribe<com.qkt.events.BrokerEvent.PositionReconciled> { seen.add(it) }
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = MT5Symbol(profile.symbolPolicy),
            bus = bus,
            strategyName = "hedge-straddle",
        ).recover()
        assertThat(seen).hasSize(1)
        // The ledger keys books by PROFILE:SYMBOL, so the bare venue symbol never matched one.
        assertThat(seen.single().symbol).isEqualTo("${profile.name.uppercase()}:XAUUSD")
        assertThat(seen.single().newQty).isEqualByComparingTo("-0.10")
        assertThat(seen.single().source).isEqualTo("mt5:${profile.name}")
    }

    @Test
    fun `a sibling's position under the shared magic publishes no correction for this strategy (#1103)`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":43,"symbol":"XAGUSDm","type":1,"volume":"0.01","price_open":"62.8",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-pairs-xau-xag"}]""",
            ),
        )
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val seen = mutableListOf<com.qkt.events.BrokerEvent.PositionReconciled>()
        bus.subscribe<com.qkt.events.BrokerEvent.PositionReconciled> { seen.add(it) }
        val seeded = mutableListOf<Long>()
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = MT5Symbol(profile.symbolPolicy),
            bus = bus,
            strategyName = "hedge-straddle",
            seedOrphan = { ticket, _, _ -> seeded.add(ticket) },
            siblingsLookup = { listOf("pairs-xau-xag") },
        ).recover()
        // The sibling's own recovery reconciles it; this strategy's ledger could only warn.
        assertThat(seen).isEmpty()
        assertThat(seeded).isEmpty()
    }

    @Test
    fun `a session-scoped recovery publishes a correction for every position`() {
        server.enqueue(
            MockResponse().setBody(
                """[{"ticket":43,"symbol":"XAGUSDm","type":1,"volume":"0.01","price_open":"62.8",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"dsl-pairs-xau-xag"},
                    {"ticket":44,"symbol":"XAUUSDm","type":0,"volume":"0.02","price_open":"4300.0",
                    "sl":"0","tp":"0","profit":"0","magic":10001,
                    "time_msc":"1748513200","comment":"bot-manual"}]""",
            ),
        )
        val bus = EventBus(FixedClock(time = 0L), MonotonicSequenceGenerator())
        val seen = mutableListOf<com.qkt.events.BrokerEvent.PositionReconciled>()
        bus.subscribe<com.qkt.events.BrokerEvent.PositionReconciled> { seen.add(it) }
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = MT5Symbol(profile.symbolPolicy),
            bus = bus,
        ).recover()
        assertThat(seen.map { it.symbol }).containsExactly("EXNESS:XAGUSD", "EXNESS:XAUUSD")
    }
}
