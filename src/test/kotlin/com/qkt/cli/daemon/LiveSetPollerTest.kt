package com.qkt.cli.daemon

import com.qkt.common.FixedClock
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.sets.DynamicSetFixtures
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.dsl.sets.DynamicSetHost
import com.qkt.dsl.sets.SetCatalog
import com.qkt.dsl.sets.SymbolSetSpec
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.InstrumentMeta
import com.qkt.instrument.universe.MembershipChange
import com.qkt.instrument.universe.VenueCatalog
import com.qkt.marketdata.Tick
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LiveSetPollerTest {
    private val journal = mutableListOf<SetMembershipChanged>()

    /** The rotation set planned live over its first two minutes: only A is a candidate, B and C are not fed yet. */
    private val host =
        DynamicSetFixtures.plan(minutes = 1).strategy(emptyMap(), live = true) { journal += it } as DynamicSetHost
    private val ctx = testStrategyContext(strategyId = "rotation")
    private var listed: List<InstrumentMeta> = DynamicSetFixtures.rows
    private var listingFails = false
    private val subscribed = mutableListOf<Collection<String>>()
    private var refuse = emptySet<String>()
    private val released = mutableListOf<String>()

    private val catalog =
        object : SetCatalog {
            override val source = "the venue listing"
            override val survivorsOnly = false

            override fun venue(
                alias: String,
                spec: SymbolSetSpec.Filtered,
            ): VenueCatalog {
                check(!listingFails) { "gateway down" }
                return VenueCatalog(listed)
            }
        }

    private val poller =
        LiveSetPoller(
            host,
            catalog,
            subscribe = { symbols ->
                subscribed += symbols
                symbols.filterTo(HashSet()) { it !in refuse }
            },
            unsubscribe = { released += it },
        )

    private fun membersAt(minute: Int): List<String> {
        val membership = host.hosted.single().membership
        return membership.membersAt(T0 + minute * MINUTE)
    }

    @Test
    fun `a poll subscribes the next candidates before they can join, and lists only what is fed`() {
        assertThat(host.declaredMembers("s")).containsExactly("A")
        assertThat(membersAt(2)).containsExactly("A")
        poller.poll(T0)
        assertThat(subscribed.single()).containsExactly("BACKTEST:B", "BACKTEST:C")
        assertThat(membersAt(2)).containsExactly("B", "A")
        assertThat(membersAt(4)).containsExactly("C", "B")
        poller.poll(T0 + MINUTE)
        assertThat(subscribed).hasSize(1)
    }

    @Test
    fun `a candidate that cannot be subscribed never joins and is journalled at the next tick`() {
        refuse = setOf("BACKTEST:C")
        poller.poll(T0)
        poller.poll(T0 + MINUTE)
        assertThat(subscribed.last()).containsExactly("BACKTEST:C")
        assertThat(membersAt(4)).containsExactly("B", "A")
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) {
            hub.register(k, host.retentionByKey[k] ?: 1, "rotation")
        }
        host.journalTo {}
        host.bindToHub(hub, ctx) {}
        host.onTick(Tick("BACKTEST:A", BigDecimal("101"), T0), ctx) {}
        assertThat(journal.first().change).isEqualTo(MembershipChange.UNAVAILABLE)
        assertThat(journal.first().member).isEqualTo("BACKTEST:C")
        assertThat(journal.first().strategyId).isEqualTo("rotation")
        assertThat(journal.first().windowEndMs).isEqualTo(T0)
        assertThat(journal.count { it.change == MembershipChange.UNAVAILABLE }).isEqualTo(1)
        refuse = emptySet()
        poller.poll(T0 + 2 * MINUTE)
        assertThat(membersAt(4)).containsExactly("C", "B")
    }

    @Test
    fun `a contract that expired or left the listing is let go, unless the set still has it active`() {
        poller.poll(T0)
        host.hosted
            .single()
            .membership
            .at(T0 + 4 * MINUTE) { emptySet() }
        listed = DynamicSetFixtures.rows.filterNot { it.qktSymbol == "BACKTEST:B" }
        poller.poll(T0 + 1_441 * MINUTE)
        assertThat(released).containsExactly("BACKTEST:A")
        poller.poll(T0 + 1_442 * MINUTE)
        assertThat(released).hasSize(1)
    }

    @Test
    fun `a contract subscribed after the start is ranked on the market figures it has traded since`() {
        val seen = mutableListOf<SetMembershipChanged>()
        val plan = DynamicSetFixtures.plan(DynamicSetFixtures.marketSource, minutes = 1)
        val market = plan.strategy(emptyMap(), live = true) { seen += it } as DynamicSetHost
        assertThat(market.declaredMembers("s")).containsExactly("A")
        LiveSetPoller(market, catalog, subscribe = { it.toSet() }).poll(T0)
        val hub = CandleHub()
        for (k in market.declaredStreams.values.toSet() + market.retentionByKey.keys) {
            hub.register(k, market.retentionByKey[k] ?: 1, "rotation")
        }
        market.bindToHub(hub, ctx) {}
        DynamicSetFixtures.marketTape().forEach { market.onTick(it, ctx) {} }
        assertThat(seen.filter { it.change == MembershipChange.JOIN }.map { it.member to it.windowEndMs })
            .containsExactly("BACKTEST:A" to T0 + MINUTE, "BACKTEST:B" to T0 + 3 * MINUTE)
    }

    @Test
    fun `a listing that cannot be read keeps the members, and the poller runs on its own thread until closed`() {
        listingFails = true
        poller.poll(T0)
        assertThat(subscribed).isEmpty()
        assertThat(membersAt(2)).containsExactly("A")
        listingFails = false
        listed = emptyList()
        poller.poll(T0)
        assertThat(subscribed).isEmpty()
        assertThat(membersAt(2)).containsExactly("A")
        listed = DynamicSetFixtures.rows
        val running = poller.start(5, FixedClock(T0))
        val deadline = System.nanoTime() + 5_000_000_000L
        while (subscribed.isEmpty() && System.nanoTime() < deadline) Thread.sleep(5)
        running.close()
        assertThat(subscribed).isNotEmpty()
    }
}
