package com.qkt.dsl.sets

import com.qkt.candles.TimeWindow
import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.compile.DecisionOrderLink
import com.qkt.dsl.compile.HubKey
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.events.Event
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import com.qkt.strategy.Signal
import com.qkt.strategy.WarmupSpec
import com.qkt.strategy.WarmupStream
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class DynamicSetHostTest {
    private val warm =
        """
        STRATEGY warm VERSION 1
        SYMBOLS
            s = BACKTEST:* WHERE kind = event TOP 2 BY symbol DESC DYNAMIC EVERY 1m WARMUP 3 BARS
        RULES
            FOR EACH m IN s DO
              WHEN m.close > sma(m.close, 3) THEN BUY m SIZING 1
        """.trimIndent()

    private val keyB = HubKey("BACKTEST", "B", "1m")
    private val hub = CandleHub()
    private val signals = mutableListOf<Signal>()
    private val links = mutableListOf<DecisionOrderLink?>()
    private val published = mutableListOf<Event>()
    private val host = DynamicSetFixtures.plan(warm).strategy(emptyMap()) as DynamicSetHost
    private val ctx = testStrategyContext(strategyId = "warm")

    /** Registers the host's streams and binds it, as the pipeline's strategy binder does. */
    private fun bind() {
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) {
            hub.register(k, host.retentionByKey[k] ?: 1, "warm")
        }
        host.journalTo { published += it }
        host.bindToHub(hub, ctx) { signal ->
            signals += signal
            links += host.onOrderSubmitted(signal, "o${signals.size}")
        }
    }

    /** [codes]' ticks at the start of minute [m], rising a point a minute, delivered as the pipeline does. */
    private fun minute(
        m: Int,
        codes: List<String> = listOf("A", "B", "C"),
    ) {
        for (code in codes) {
            val tick = Tick("BACKTEST:$code", BigDecimal(100 + m), T0 + m * MINUTE)
            host.onTick(tick, ctx) { signals += it }
            hub.feed(tick)
        }
    }

    private fun boughtSymbols(): List<String> = signals.filterIsInstance<Signal.Buy>().map { it.symbol }

    @Test
    fun `every candidate keeps a member's history, and a member that joins late is warm from it`() {
        assertThat(host.declaredStreams.keys).containsExactly("s.A", "s.B", "s.C")
        assertThat(host.retentionByKey[keyB]).isGreaterThanOrEqualTo(3)
        assertThat(host.perStreamWarmup[WarmupStream("BACKTEST:B", TimeWindow.ONE_MINUTE)])
            .isInstanceOf(WarmupSpec.Bars::class.java)
        bind()
        (0..2).forEach(::minute)
        assertThat(signals).isEmpty()
        minute(3)
        assertThat(boughtSymbols()).containsExactly("BACKTEST:A", "BACKTEST:B")
        assertThat(links).allSatisfy { assertThat(it?.orderId).startsWith("o") }
        assertThat(published.map { it as SetMembershipChanged }.map { Triple(it.member, it.change, it.windowEndMs) })
            .containsExactly(
                Triple("BACKTEST:A", MembershipChange.JOIN, T0),
                Triple("BACKTEST:B", MembershipChange.JOIN, T0 + 2 * MINUTE),
            )
    }

    @Test
    fun `a member never fires at the close it joined, only from the next one`() {
        bind()
        (0..3).forEach(::minute)
        val before = signals.size
        minute(4)
        assertThat(signals.drop(before).filterIsInstance<Signal.Buy>().map { it.symbol }).doesNotContain("BACKTEST:C")
        assertThat(published.takeLast(2).map { it as SetMembershipChanged }.map { it.member to it.change })
            .containsExactly("BACKTEST:A" to MembershipChange.LEAVE, "BACKTEST:C" to MembershipChange.JOIN)
        minute(5)
        assertThat(signals.drop(before).filterIsInstance<Signal.Buy>().map { it.symbol }).contains("BACKTEST:C")
    }

    @Test
    fun `order outcomes reach the instance that sent the order, and a member with no history still joins`() {
        bind()
        (0..1).forEach { minute(it, listOf("A", "C")) }
        (2..3).forEach(::minute)
        assertThat(published).hasSize(2)
        val id = links.filterNotNull().first().orderId
        assertThat(host.onOrderSubmitted(Signal.Buy("BACKTEST:A", BigDecimal.ONE), "stray")).isNull()
        assertThat(host.onExitOrderUnfilled(id)).isNull()
        host.onOrderFilled(id)
        host.onOrderTerminal(id)
        host.onOrderRejected(id)
        host.onPositionStateChanged("BACKTEST:A", nowHeld = false, atMs = T0 + 4 * MINUTE)
        host.clearRuleEdges()
        minute(4)
        assertThat(published.map { (it as SetMembershipChanged).member }).contains("BACKTEST:C")
    }
}
