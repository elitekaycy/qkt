package com.qkt.dsl.compile

import com.qkt.app.StructureFixtures
import com.qkt.common.FixedClock
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.marketdata.Candle
import com.qkt.strategy.StructureLegPosition
import com.qkt.strategy.StructurePosition
import com.qkt.strategy.StructureState
import com.qkt.strategy.StructureView
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

/** 0.1 of a 9OCT26 81000/78000 put spread, contract size 1, opened at 646 and 219. */
class StructureFieldCompilerTest {
    private val candle =
        Candle("X", BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ZERO, 0L, 1L)
    private val compiler = ExprCompiler(structures = StructureSupport(setOf("ps")))

    private fun leg(
        symbol: String,
        quantity: String,
        entry: String,
        held: String = quantity,
        realized: String = "0",
    ) = StructureLegPosition(
        symbol,
        BigDecimal.ONE,
        StructureFixtures.OCT9,
        BigDecimal(quantity),
        BigDecimal(entry),
        BigDecimal(held),
        BigDecimal(realized),
    )

    private val credit = listOf(leg(StructureFixtures.P81, "-0.1", "646"), leg(StructureFixtures.P78, "0.1", "219"))

    private fun eval(
        expr: com.qkt.dsl.ast.ExprAst,
        legs: List<StructureLegPosition> = credit,
        state: StructureState = StructureState.OPEN,
        marks: Map<String, String> = mapOf(StructureFixtures.P81 to "500", StructureFixtures.P78 to "150"),
        live: Boolean = true,
    ): Value {
        val view =
            object : StructureView {
                override fun live(alias: String) =
                    StructurePosition("ps-1", "ps", state, BigDecimal("0.1"), legs).takeIf { live && alias == "ps" }

                override fun all() = listOfNotNull(live("ps"))

                override fun mark(symbol: String) = marks[symbol]?.let(::BigDecimal)
            }
        val clock = FixedClock(StructureFixtures.OCT9 - 54 * 3_600_000L)
        val ctx =
            testStrategyContext(clock = clock, instruments = StructureFixtures.registry).copy(structures = view)
        return compiler.compile(expr).evaluate(EvalContext(candle, emptyMap(), emptyMap(), ctx))
    }

    private fun num(
        source: StateSource,
        legs: List<StructureLegPosition> = credit,
        marks: Map<String, String> = mapOf(StructureFixtures.P81 to "500", StructureFixtures.P78 to "150"),
    ) = (eval(StateAccessor(source, "ps"), legs, marks = marks) as Value.Num).v

    @Test
    fun `a credit spread's credit, max loss, P&L and days to expiry`() {
        // Credit 64.6 - 21.9; loss 0.1 x 3000 width less credit; P&L 0.1 x (646 - 500) - 0.1 x (219 - 150).
        assertThat(num(StateSource.STRUCTURE_CREDIT)).isEqualByComparingTo("42.7")
        assertThat(num(StateSource.STRUCTURE_MAX_LOSS)).isEqualByComparingTo("257.3")
        assertThat(num(StateSource.POSITION_PNL)).isEqualByComparingTo("7.7")
        assertThat(num(StateSource.STRUCTURE_PNL_PCT).toDouble()).isCloseTo(100 * 7.7 / 42.7, within(1e-12))
        assertThat(num(StateSource.STRUCTURE_DTE)).isEqualByComparingTo("2.25")
        assertThat((eval(PositionRef("ps")) as Value.Num).v).isEqualByComparingTo("0.1")
    }

    @Test
    fun `a debit spread has a negative credit, its debit as max loss, and a gain reads positive`() {
        val debit = listOf(leg(StructureFixtures.P81, "0.1", "646"), leg(StructureFixtures.P78, "-0.1", "219"))
        val marks = mapOf(StructureFixtures.P81 to "700", StructureFixtures.P78 to "200")

        assertThat(num(StateSource.STRUCTURE_CREDIT, debit)).isEqualByComparingTo("-42.7")
        assertThat(num(StateSource.STRUCTURE_MAX_LOSS, debit)).isEqualByComparingTo("42.7")
        // 0.1 x (700 - 646) + 0.1 x (219 - 200) = 7.3 on 42.7 paid.
        assertThat(
            num(StateSource.STRUCTURE_PNL_PCT, debit, marks).toDouble(),
        ).isCloseTo(100 * 7.3 / 42.7, within(1e-12))
    }

    @Test
    fun `a settled leg counts its realized P&L, and a held leg without a mark leaves P&L undefined`() {
        val settled =
            listOf(leg(StructureFixtures.P81, "-0.1", "646"), leg(StructureFixtures.P78, "0.1", "219", "0", "-21.9"))

        assertThat(num(StateSource.POSITION_PNL, settled)).isEqualByComparingTo("-7.3")
        assertThat(eval(StateAccessor(StateSource.POSITION_PNL, "ps"), marks = emptyMap())).isEqualTo(Value.Undefined)
    }

    @Test
    fun `fields are undefined unless the structure is open, while its size counts from acceptance`() {
        assertThat(eval(StateAccessor(StateSource.STRUCTURE_CREDIT, "ps"), state = StructureState.PENDING))
            .isEqualTo(Value.Undefined)
        assertThat((eval(PositionRef("ps"), state = StructureState.PENDING) as Value.Num).v).isEqualByComparingTo("0.1")
        assertThat(eval(StateAccessor(StateSource.STRUCTURE_DELTA, "ps"), live = false)).isEqualTo(Value.Undefined)
        assertThat((eval(PositionRef("ps"), live = false) as Value.Num).v).isEqualByComparingTo("0")
    }

    @Test
    fun `a structure field needs a structure alias, and a structure has no stream-only fields`() {
        assertThatThrownBy { compiler.compile(StateAccessor(StateSource.STRUCTURE_DELTA, "gold")) }
            .isInstanceOf(CompileError::class.java)
            .hasMessage("POSITION.gold.delta is a structure field; gold opens no structure")
        assertThatThrownBy { compiler.compile(StateAccessor(StateSource.POSITION_MFE, "ps")) }
            .isInstanceOf(CompileError::class.java)
            .hasMessageStartingWith("POSITION.ps.mfe is not a structure field")
    }
}
