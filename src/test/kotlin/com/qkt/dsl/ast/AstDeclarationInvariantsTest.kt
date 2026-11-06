package com.qkt.dsl.ast

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** AST declarations refuse malformed values at construction, so no compiler stage sees them. */
class AstDeclarationInvariantsTest {
    private val one = NumLit(BigDecimal.ONE)

    private fun rejects(
        message: String,
        build: () -> Any,
    ) {
        assertThatThrownBy { build() }.isInstanceOf(IllegalArgumentException::class.java).hasMessageContaining(message)
    }

    @Test
    fun `a constant needs a name`() {
        assertThat(ConstantDecl("k", BigDecimal.TEN).value).isEqualByComparingTo("10")
        rejects("ConstantDecl.name must not be blank") { ConstantDecl(" ", BigDecimal.TEN) }
    }

    @Test
    fun `a sync group needs two unique members and a positive timeout`() {
        assertThat(SyncGroupDecl(listOf("a", "b"), 200L).timeoutMs).isEqualTo(200L)
        rejects("SyncGroupDecl needs at least 2 aliases, got 1") { SyncGroupDecl(listOf("a")) }
        rejects("SyncGroupDecl.timeoutMs must be positive when present: 0") { SyncGroupDecl(listOf("a", "b"), 0L) }
        rejects("SyncGroupDecl aliases must be unique") { SyncGroupDecl(listOf("a", "a")) }
    }

    @Test
    fun `stream and series declarations need every part`() {
        rejects("StreamDecl.broker must not be blank") { StreamDecl("b", "", "X", "1m") }
        rejects("StreamDecl.symbol must not be blank") { StreamDecl("b", "B", "", "1m") }
        rejects("StreamDecl.timeframe must not be blank") { StreamDecl("b", "B", "X", "") }
        rejects("StreamDecl.warmupBars must be > 0 if set: 0") { StreamDecl("b", "B", "X", "1m", warmupBars = 0) }
        rejects("SeriesDecl.alias must not be blank") { SeriesDecl("", SeriesSource.ACCOUNT_EQUITY, "1h") }
        rejects("SeriesDecl.timeframe must not be blank") { SeriesDecl("eq", SeriesSource.ACCOUNT_EQUITY, "") }
    }

    @Test
    fun `strategy-level names must be unique and present`() {
        val params = listOf(ParamDecl("p", one), ParamDecl("p", one))
        rejects("duplicate PARAM name") {
            StrategyAst("s", 1, emptyList(), emptyList(), emptyList(), params = params, defaults = null, rules = emptyList())
        }
        rejects("LetDecl.name must not be blank") { LetDecl("", one) }
    }

    @Test
    fun `portfolio pieces need names, states and a positive rebalance period`() {
        rejects("ImportClause.path must not be blank") { ImportClause("", "a") }
        rejects("ImportClause.alias must not be blank") { ImportClause("x.qkt", "") }
        rejects("RegimeBlock.name must not be blank") { RegimeBlock("", listOf(RegimeDefaultState("calm"))) }
        rejects("RegimeBlock 'r' must declare at least one STATE") { RegimeBlock("r", emptyList()) }
        rejects("RegimeState.name must not be blank") { RegimeDefaultState("") }
        rejects("RegimeState.name must not be blank") { RegimeConditionalState("", BoolLit(true)) }
        rejects("AllocateBlock.rebalanceEveryDurationMs must be > 0") {
            AllocateBlock(PortfolioAllocationMethod.REGIME_WEIGHTED, 0L, emptyMap())
        }
    }

    @Test
    fun `windows, durations, stacks, sequences and clock times are range-checked`() {
        rejects("SnapshotTPast.n must be > 0: 0") { SnapshotTPast(0) }
        rejects("SinceTPast.n must be > 0: 0") { SinceTPast(0) }
        rejects("DURATION must be > 0 ms: 0") { DurationAst(0L) }
        rejects("STACK count must be >= 1: 0") { StackSpacing(0, one, StackDirection.ABOVE) }
        rejects("STACK layer list must not be empty") { StackLayers(emptyList()) }
        rejects("TimeOfDay.hour must be 0-23: 24") { TimeOfDay(24, 0) }
        rejects("TimeOfDay.minute must be 0-59: 60") { TimeOfDay(1, 60) }
        rejects("TimeOfDay.second must be 0-59: 60") { TimeOfDay(1, 0, 60) }
        val stage = SequenceStageDecl("a", null, BoolLit(true))
        rejects("SequenceDecl.name must not be blank") { SequenceDecl("", "g", listOf(stage, stage)) }
        rejects("SequenceDecl.stream must not be blank") { SequenceDecl("s", "", listOf(stage, stage)) }
        rejects("SequenceStageDecl.name must not be blank") { SequenceStageDecl("", null, BoolLit(true)) }
    }

    @Test
    fun `a rule copy keeps its identity apart from the source line`() {
        val rule = WhenThen(BoolLit(true), CloseAll, line = 4)
        val moved = rule.copy(line = 9)

        assertThat(moved).isEqualTo(rule)
        assertThat(moved.hashCode()).isEqualTo(rule.hashCode())
        assertThat(moved.line).isEqualTo(9)
        assertThat(rule.copy(cond = BoolLit(false))).isNotEqualTo(rule)
        assertThat(rule).isNotEqualTo("WHEN TRUE THEN CLOSE_ALL")
    }
}
