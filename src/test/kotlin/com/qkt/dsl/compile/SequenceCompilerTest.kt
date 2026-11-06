package com.qkt.dsl.compile

import com.qkt.dsl.ast.AccountRef
import com.qkt.dsl.ast.Aggregate
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.BinOp
import com.qkt.dsl.ast.BinaryOp
import com.qkt.dsl.ast.BoolLit
import com.qkt.dsl.ast.CalendarWindow
import com.qkt.dsl.ast.CaseWhen
import com.qkt.dsl.ast.Cmp
import com.qkt.dsl.ast.CmpOp
import com.qkt.dsl.ast.CooldownRef
import com.qkt.dsl.ast.CrossDir
import com.qkt.dsl.ast.Crosses
import com.qkt.dsl.ast.ExprAst
import com.qkt.dsl.ast.FuncCall
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.IsNull
import com.qkt.dsl.ast.LastTradingDayOfMonth
import com.qkt.dsl.ast.NowAccessor
import com.qkt.dsl.ast.NowField
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.PositionRef
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SequenceDecl
import com.qkt.dsl.ast.SequenceStageDecl
import com.qkt.dsl.ast.SessionWindow
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StateAccessor
import com.qkt.dsl.ast.StateSource
import com.qkt.dsl.ast.StreakRef
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.dsl.ast.StringLit
import com.qkt.dsl.ast.TradesRef
import com.qkt.dsl.ast.UnOp
import com.qkt.dsl.ast.UnaryOp
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** [SequenceCompiler]: stage conditions compile and every stream a stage reads joins the warm-up gate. */
class SequenceCompilerTest {
    private val streams =
        listOf("gold", "silver", "eur", "oil", "btc", "eth", "spx", "ndx", "dax")
            .associateWith { HubKey("BACKTEST", it.uppercase(), "1m") }

    private fun num(v: Int) = NumLit(BigDecimal(v))

    private fun field(alias: String) = StreamFieldRef(alias, "close")

    private fun gt(lhs: ExprAst) = CmpOp(Cmp.GT, lhs, num(0))

    private fun stage(
        name: String,
        condition: ExprAst,
    ) = SequenceStageDecl(name, null, condition)

    private fun compile(vararg sequences: SequenceDecl): SequenceRuntime =
        SequenceCompiler.compile(sequences.toList(), streams, ExprCompiler(), LetResolver(emptyList()))

    /** The aliases the runtime asks to be warm before it evaluates the sequence on a gold bar. */
    private fun warmGate(runtime: SequenceRuntime): Set<String> {
        val one = BigDecimal.ONE
        val candle = Candle("BACKTEST:GOLD", one, one, one, one, BigDecimal.ZERO, 0L, 1L)
        val ec = EvalContext(candle, streams, emptyMap(), testStrategyContext())
        var asked: Set<String> = emptySet()
        val neverWarm: (Set<String>) -> Boolean = { aliases ->
            asked = aliases
            false
        }
        runtime.onCandle(candle, ec, isWarm = neverWarm)
        return asked
    }

    @Test
    fun `every stream read inside nested operators joins the sequence warm-up gate`() {
        val composite =
            BinaryOp(
                BinOp.AND,
                gt(BinaryOp(BinOp.SUB, field("silver"), UnaryOp(UnOp.NEG, field("eur")))),
                BinaryOp(
                    BinOp.OR,
                    Crosses(CrossDir.ABOVE, field("oil"), FuncCall("ABS", listOf(field("btc")))),
                    BinaryOp(
                        BinOp.OR,
                        Between(field("eth"), num(1), field("spx")),
                        BinaryOp(
                            BinOp.OR,
                            InList(field("ndx"), listOf(num(1), num(2))),
                            CaseWhen(
                                listOf(IsNull(field("dax"), false) to BoolLit(true)),
                                CmpOp(Cmp.GT, PositionRef("gold"), num(0)),
                            ),
                        ),
                    ),
                ),
            )
        val runtime = compile(SequenceDecl("setup", "gold", listOf(stage("a", composite), stage("b", gt(field("gold"))))))

        assertThat(warmGate(runtime))
            .containsExactlyInAnyOrder("gold", "silver", "eur", "oil", "btc", "eth", "spx", "ndx", "dax")
    }

    @Test
    fun `stream-free leaves add nothing beyond the sequence's own stream`() {
        val leaves: List<ExprAst> =
            listOf(
                num(1),
                StringLit("x"),
                AccountRef("equity"),
                StreakRef("wins"),
                TradesRef("today"),
                CooldownRef("remaining_s"),
                StateAccessor(StateSource.POSITION_REALIZED_PNL, "gold"),
                NowAccessor(NowField.HOUR_UTC),
                StackEntryRef,
                SequenceAccessor("other", null, "stage"),
            )
        val booleans: List<ExprAst> =
            listOf(BoolLit(true), CalendarWindow(1, 1, 2, 1), SessionWindow(8, 0, 16, 0), LastTradingDayOfMonth)
        val condition =
            CaseWhen(
                booleans.map { it to BoolLit(true) },
                InList(num(0), leaves),
            )
        val runtime = compile(SequenceDecl("setup", "gold", listOf(stage("a", condition), stage("b", BoolLit(true)))))

        assertThat(warmGate(runtime)).containsExactly("gold")
    }

    @Test
    fun `an aggregate's series stream joins the warm-up gate`() {
        val parsed = (com.qkt.dsl.parse.Dsl.parse(AGG_SOURCE) as com.qkt.dsl.parse.ParseResult.Success).value
        val aggregate = (parsed.sequences.single().stages.first().condition as CmpOp).lhs as Aggregate
        assertThat(aggregate.fn).isEqualTo(AggFn.MAX)
        val runtime = SequenceCompiler.compile(parsed.sequences, streams, ExprCompiler(), LetResolver(parsed.lets))

        assertThat(warmGate(runtime)).containsExactlyInAnyOrder("gold", "silver")
    }

    @Test
    fun `duplicate sequence names are rejected`() {
        val seq = SequenceDecl("setup", "gold", listOf(stage("a", BoolLit(true)), stage("b", BoolLit(true))))

        assertThatThrownBy { compile(seq, seq) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate SEQUENCE name")
    }

    @Test
    fun `duplicate stage names inside one sequence are rejected`() {
        val seq = SequenceDecl("setup", "gold", listOf(stage("a", BoolLit(true)), stage("a", BoolLit(false))))

        assertThatThrownBy { compile(seq) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("Duplicate STAGE name in SEQUENCE 'setup'")
    }

    @Test
    fun `a sequence on an undeclared stream is rejected`() {
        val seq = SequenceDecl("setup", "copper", listOf(stage("a", BoolLit(true)), stage("b", BoolLit(true))))

        assertThatThrownBy { compile(seq) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("Unknown SEQUENCE stream alias: copper")
    }

    private companion object {
        val AGG_SOURCE =
            """
            STRATEGY s VERSION 1
            SYMBOLS
                gold = BACKTEST:GOLD EVERY 1m
                silver = BACKTEST:SILVER EVERY 1m
            SEQUENCE setup ON gold {
                STAGE a: max(silver.close) SINCE T-3 > 0
                STAGE b: gold.close > 0
            }
            RULES
                WHEN SEQUENCE.setup.complete THEN BUY gold SIZING 1
            """.trimIndent()
    }
}
