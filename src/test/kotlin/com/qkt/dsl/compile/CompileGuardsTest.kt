package com.qkt.dsl.compile

import com.qkt.common.Side
import com.qkt.dsl.ast.AggFn
import com.qkt.dsl.ast.Between
import com.qkt.dsl.ast.DirRel
import com.qkt.dsl.ast.DirSense
import com.qkt.dsl.ast.InList
import com.qkt.dsl.ast.LetDecl
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.SequenceAccessor
import com.qkt.dsl.ast.SinceTPast
import com.qkt.dsl.ast.StreamFieldRef
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import com.qkt.marketdata.Candle
import com.qkt.strategy.testStrategyContext
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

/** Small compile-time and run-time guards across the compiler's helpers. */
class CompileGuardsTest {
    private val one = BigDecimal.ONE
    private val gold = HubKey("BACKTEST", "XAUUSD", "1m")
    private val candle = Candle("BACKTEST:XAUUSD", one, one, one, one, BigDecimal.ZERO, 0L, 1L)
    private val ctx = EvalContext(candle, mapOf("g" to gold), emptyMap(), testStrategyContext())

    /** The bar has no bid, so `g.bid` is undefined. */
    private val undefined = StreamFieldRef("g", "bid")

    private fun num(v: String) = NumLit(BigDecimal(v))

    @Test
    fun `a sync group needs two named members and a positive timeout`() {
        val silver = HubKey("BACKTEST", "XAGUSD", "1m")

        assertThatThrownBy {
            SyncGroupKey(
                mapOf("g" to gold),
                null,
            )
        }.hasMessageContaining("needs at least 2 members, got 1")
        assertThatThrownBy {
            SyncGroupKey(mapOf("g" to gold, " " to silver), null)
        }.hasMessageContaining("alias keys must not be blank")
        assertThatThrownBy {
            SyncGroupKey(mapOf("g" to gold, "s" to silver), 0L)
        }.hasMessageContaining("timeoutMs must be positive")
    }

    @Test
    fun `a rolling minimum reads the smallest of the window and forgets it on reset`() {
        val state = AggregateBinding.Bag.stateFor(AggFn.MIN, SinceTPast(2))
        listOf("5", "3", "4").forEach { state.update(BigDecimal(it)) }

        assertThat(state.read()).isEqualByComparingTo("3")
        state.reset()
        assertThat(state.read()).isNull()
    }

    @Test
    fun `range and membership tests on an undefined value are undefined`() {
        val exprs = ExprCompiler()

        assertThat(exprs.compile(InList(undefined, listOf(num("1")))).evaluate(ctx)).isEqualTo(Value.Undefined)
        assertThat(exprs.compile(Between(undefined, num("0"), num("2"))).evaluate(ctx)).isEqualTo(Value.Undefined)
    }

    @Test
    fun `an unknown sequence accessor is refused`() {
        assertThatThrownBy { SequenceAccessorCompiler.compile(SequenceAccessor("s", null, "nope")) }
            .hasMessageContaining("Unknown SEQUENCE accessor: SEQUENCE.s.nope")
        assertThatThrownBy { SequenceAccessorCompiler.compile(SequenceAccessor("s", "a", "nope")) }
            .hasMessageContaining("Unknown SEQUENCE stage accessor: SEQUENCE.s.a.nope")
    }

    @Test
    fun `a GTD deadline must evaluate to a number`() {
        val req = OrderRequest.Limit("o", "BACKTEST:XAUUSD", Side.BUY, one, one, TimeInForce.GTC, 0L)

        assertThatThrownBy { stampGtdDeadline(req, ExprCompiler().compile(undefined), ctx) }
            .hasMessageContaining("TIF GTD expression evaluated to")
    }

    @Test
    fun `latch distances must be literals and only entry orders take a new quantity`() {
        val stopLimit = OrderRequest.StopLimit("o", "S", Side.BUY, one, one, one, TimeInForce.GTC, 0L)

        assertThatThrownBy { LatchOrderMath.signedDist(DirRel(DirSense.WITH, undefined)) }
            .hasMessageContaining("LATCH distances must be compile-time constants")
        assertThat(LatchOrderMath.withQty(stopLimit, BigDecimal.TEN)).isSameAs(stopLimit)
    }

    @Test
    fun `duplicate LET names and a one-constituent basket are refused`() {
        assertThatThrownBy { LetResolver(listOf(LetDecl("a", num("1")), LetDecl("a", num("2")))) }
            .hasMessageContaining("Duplicate LET name in: [a, a]")
        assertThatThrownBy { BasketCompositor("BASKET:x", listOf("A")) }
            .hasMessageContaining("BasketCompositor needs at least 2 constituents, got 1")
    }
}
