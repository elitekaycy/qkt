package com.qkt.dsl.sets

import com.qkt.dsl.compile.CandleHub
import com.qkt.dsl.sets.DynamicSetFixtures.MINUTE
import com.qkt.dsl.sets.DynamicSetFixtures.T0
import com.qkt.events.SetMembershipChanged
import com.qkt.instrument.universe.MemberChange
import com.qkt.instrument.universe.MembershipChange
import com.qkt.marketdata.Tick
import com.qkt.persistence.FileStatePersistor
import com.qkt.positions.Position
import com.qkt.positions.StrategyPositionView
import com.qkt.strategy.OpenOrderView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

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

    @TempDir
    lateinit var tmp: Path

    /** A host restarted over [owned] (what its set held before), with A held now, or only [working] an entry. */
    private fun restarted(
        owned: List<String>,
        working: Boolean = false,
    ): List<Triple<String, MembershipChange, Int?>> {
        val journal = mutableListOf<SetMembershipChanged>()
        val host = DynamicSetFixtures.plan().strategy(emptyMap(), live = true) { journal += it } as DynamicSetHost
        val persistor = FileStatePersistor(tmp)
        SetOwnership.save(persistor, "rotation", "s", owned)
        host.bindStatePersistor("rotation", persistor)
        val positions =
            object : StrategyPositionView {
                override fun positionFor(symbol: String): Position? =
                    Position(symbol, BigDecimal.ONE, BigDecimal.ONE).takeIf { symbol == "BACKTEST:A" && !working }

                override fun allPositions(): Map<String, Position> = emptyMap()
            }
        val orders = OpenOrderView { symbol -> if (working && symbol == "BACKTEST:A") 1 else 0 }
        val ctx = testStrategyContext(strategyId = "rotation", positions = positions, openOrders = orders)
        val hub = CandleHub()
        for (k in host.declaredStreams.values.toSet() + host.retentionByKey.keys) hub.register(k, 1, "rotation")
        host.bindToHub(hub, ctx) {}
        host.onTick(Tick("BACKTEST:C", BigDecimal("99"), T0 + 4 * MINUTE), ctx) {}
        return journal.map { Triple(it.member, it.change, it.rank) }
    }

    @Test
    fun `a host that starts holding a member it owned restores it sticky and the ranked ones as members`() {
        assertThat(restarted(owned = listOf("A"))).containsExactly(
            Triple("BACKTEST:C", MembershipChange.RESTORE, 1),
            Triple("BACKTEST:B", MembershipChange.RESTORE, 2),
            Triple("BACKTEST:A", MembershipChange.RESTORE, null),
        )
        assertThat(SetOwnership.load(FileStatePersistor(tmp), "rotation", "s")).containsExactly("C", "B", "A")
        assertThat(SetOwnership.expiries(FileStatePersistor(tmp), "rotation", "s"))
            .containsOnlyKeys("A", "B", "C")
            .containsValue(T0 + 1_440 * MINUTE)
    }

    @Test
    fun `a member with only an entry order working is restored, so its fill has an instance`() {
        assertThat(restarted(owned = listOf("A"), working = true).last())
            .isEqualTo(Triple("BACKTEST:A", MembershipChange.RESTORE, null))
    }

    @Test
    fun `a position the set never owned is not restored into it`() {
        assertThat(restarted(owned = emptyList()).map { it.second }).containsOnly(MembershipChange.JOIN)
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
