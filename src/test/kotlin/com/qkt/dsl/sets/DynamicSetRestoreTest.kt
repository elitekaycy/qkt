package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MemberChange
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/** A session that starts over held positions rebuilds its sets' membership instead of joining them afresh. */
class DynamicSetRestoreTest {
    private val spec =
        SymbolSetSpec.parse("BACKTEST:* WHERE kind = event TOP 2 BY symbol DESC DYNAMIC") as SymbolSetSpec.Filtered

    @Test
    fun `the first evaluation of a restored run restores, the next ones step`() {
        val set = DynamicSetMembership(spec, DynamicSetFixtures.rows, MINUTE)
        val first = set.at(T0 + 4 * MINUTE, restore = true) { setOf("A") }
        assertThat(first?.step?.changes).containsExactly(
            MemberChange("C", MembershipChange.RESTORE, 1),
            MemberChange("B", MembershipChange.RESTORE, 2),
            MemberChange("A", MembershipChange.RESTORE, null),
        )
        val next = set.at(T0 + 5 * MINUTE, restore = true) { emptySet() }
        assertThat(next?.step?.changes).containsExactly(MemberChange("A", MembershipChange.RELEASE, null))
    }

    @Test
    fun `a host that starts holding a member restores it sticky and the ranked ones as members`() {
        val journal = mutableListOf<SetMembershipChanged>()
        val host = DynamicSetFixtures.plan().strategy(emptyMap(), live = true) { journal += it } as DynamicSetHost
        val positions =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? =
                    if (symbol == "BACKTEST:A") Position(symbol, BigDecimal.ONE, BigDecimal.ONE) else null

                override fun allPositions(): Map<String, Position> = emptyMap()
            }
        val ctx = testStrategyContext(strategyId = "rotation", positions = positions)
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) hub.register(k, 1, "rotation")
        host.bindToHub(hub, ctx) {}
        host.onTick(Tick("BACKTEST:C", BigDecimal("99"), T0 + 4 * MINUTE), ctx) {}
        assertThat(journal.map { Triple(it.member, it.change, it.rank) }).containsExactly(
            Triple("BACKTEST:C", MembershipChange.RESTORE, 1),
            Triple("BACKTEST:B", MembershipChange.RESTORE, 2),
            Triple("BACKTEST:A", MembershipChange.RESTORE, null),
        )
    }

    @Test
    fun `a host that starts flat joins its members as a fresh run does`() {
        val journal = mutableListOf<SetMembershipChanged>()
        val host = DynamicSetFixtures.plan().strategy(emptyMap(), live = true) { journal += it } as DynamicSetHost
        val ctx = testStrategyContext(strategyId = "rotation")
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) hub.register(k, 1, "rotation")
        host.bindToHub(hub, ctx) {}
        host.onTick(Tick("BACKTEST:C", BigDecimal("99"), T0 + 4 * MINUTE), ctx) {}
        assertThat(journal.map { it.change }).containsOnly(MembershipChange.JOIN)
    }

    @Test
    fun `a host resumes its members past the order-id marks a restart restored, and reports them`() {
        val host = DynamicSetFixtures.plan().strategy(emptyMap(), live = true) as DynamicSetHost
        host.resumeOrderIdMarks(mapOf("dsl-rotation.s.A-" to 4L))
        val ctx = testStrategyContext(strategyId = "rotation")
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) hub.register(k, 1, "rotation")
        host.bindToHub(hub, ctx) {}
        host.onTick(Tick("BACKTEST:A", BigDecimal("101"), T0), ctx) {}
        assertThat(host.orderIdMarks()).containsEntry("dsl-rotation.s.A-", 4L)
    }
}
