package com.qkt.app

import com.qkt.app.LatchManagerFixture.candle
import com.qkt.app.LatchManagerFixture.ec
import com.qkt.app.LatchManagerFixture.tick
import com.qkt.common.FixedClock
import com.qkt.common.Side
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchConfirm
import com.qkt.dsl.ast.LatchFirstTick
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.Ref
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.CompiledLatchEntry
import com.qkt.dsl.compile.LatchEntryBuilder
import com.qkt.dsl.compile.Value
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class LatchManagerEdgesTest {
    private val emitted = mutableListOf<OrderRequest.Limit>()
    private val manager = LatchManager(FixedClock(1_000L)) { emitted += it as OrderRequest.Limit }

    private fun entry(alias: String) =
        CompiledLatchEntry(
            alias,
            LatchEntryBuilder { direction, anchor, ctx ->
                OrderRequest.Limit(
                    id = "entry-$alias",
                    symbol = ctx.candle.symbol,
                    side = if (direction > 0) Side.BUY else Side.SELL,
                    quantity = BigDecimal.ONE,
                    limitPrice = anchor,
                    timeInForce = TimeInForce.GTC,
                    timestamp = 0L,
                )
            },
        )

    private fun latch(
        confirm: LatchConfirm = LatchFirstTick,
        reference: Value = Value.Num(BigDecimal("2000.0")),
        offset: Value = Value.Num(BigDecimal("0.50")),
        windowMs: Long = 300_000L,
        entries: List<String> = listOf("s"),
    ) = CompiledLatch(
        streamAlias = "s",
        offset = CompiledExpr { offset },
        reference = CompiledExpr { reference },
        armWindowMs = windowMs,
        name = "edge",
        entries = entries.map(::entry),
        confirm = confirm,
    )

    private fun retest() = latch(confirm = LatchRetestHold(NumLit(BigDecimal("0.10")), DurationAst(5_000L)))

    @Test
    fun `a latch whose reference or offset is not ready is never armed`() {
        manager.arm(latch(reference = Value.Undefined), ec("XAUUSD"))
        manager.arm(latch(offset = Value.Undefined), ec("XAUUSD"))

        manager.onTick(tick("XAUUSD", "2010.0", 2_000L))

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `arming without any emit path is refused`() {
        assertThatThrownBy { LatchManager(FixedClock(0L)).arm(latch(), ec("XAUUSD")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("requires an emit callback")
    }

    @Test
    fun `a retest distance that is not a constant fails loudly when evaluated`() {
        manager.arm(latch(confirm = LatchRetestHold(Ref("atr"), DurationAst(5_000L))), ec("XAUUSD"))

        assertThatThrownBy { manager.onTick(tick("XAUUSD", "2000.90", 2_000L)) }
            .hasMessageContaining("distance must be compile-time constant")
    }

    @Test
    fun `a close beyond latch expires on the first bar closing after its window`() {
        manager.arm(latch(confirm = LatchCloseBeyond, windowMs = 60_000L), ec("XAUUSD"), now = 1_000L)

        manager.onCandle(candle("XAGUSD", "30.0", 30_000L))
        manager.onCandle(candle("XAUUSD", "2000.10", 120_000L))
        manager.onCandle(candle("XAUUSD", "2010.00", 180_000L))

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `a retest that takes longer than its window restarts from the next breach`() {
        manager.arm(retest(), ec("XAUUSD"), now = 1_000L)

        manager.onTick(tick("XAUUSD", "2000.90", 2_000L))
        manager.onTick(tick("XAUUSD", "2000.00", 10_000L))
        manager.onTick(tick("XAUUSD", "2000.90", 11_000L))
        manager.onTick(tick("XAUUSD", "2000.90", 17_000L))
        assertThat(emitted).isEmpty()
        manager.onTick(tick("XAUUSD", "2000.55", 18_000L))

        assertThat(emitted.single().side).isEqualTo(Side.BUY)
        assertThat(emitted.single().limitPrice).isEqualByComparingTo("2000.50")
    }

    @Test
    fun `crossing back through the wire hands the retest to whichever side is breached`() {
        manager.arm(retest(), ec("XAUUSD"), now = 1_000L)

        manager.onTick(tick("XAUUSD", "2000.90", 2_000L))
        manager.onTick(tick("XAUUSD", "2000.00", 2_500L))
        manager.onTick(tick("XAUUSD", "2000.90", 2_600L))
        manager.onTick(tick("XAUUSD", "1999.30", 3_000L))
        assertThat(emitted).isEmpty()
        manager.onTick(tick("XAUUSD", "1999.45", 4_000L))

        assertThat(emitted.single().side).isEqualTo(Side.SELL)
        assertThat(emitted.single().limitPrice).isEqualByComparingTo("1999.50")
    }

    @Test
    fun `entries on other streams anchor at that stream's last price and are skipped without one`() {
        manager.arm(latch(entries = listOf("s", "silver", "ghost")), ec("XAUUSD"))

        manager.onTick(tick("BACKTEST:XAGUSD", "25.10", 1_500L))
        manager.onTick(tick("XAUUSD", "2000.60", 2_000L))

        assertThat(emitted.map { it.id to it.limitPrice.toPlainString() })
            .containsExactly("entry-s" to "2000.50", "entry-silver" to "25.10")
    }
}
