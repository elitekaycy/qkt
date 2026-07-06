package com.qkt.app

import com.qkt.common.Side
import com.qkt.dsl.ast.DurationAst
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchTimeInBreach
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.compile.CompiledExpr
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.CompiledLatchEntry
import com.qkt.dsl.compile.LatchEntryBuilder
import com.qkt.dsl.compile.Value
import com.qkt.execution.OrderRequest
import com.qkt.execution.TimeInForce
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class LatchManagerTest {
    @Test
    fun `up-break fans out the entries as BUY orders`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(ref = "2000.0", offset = "0.50", windowMs = 300_000L),
            ec = ec,
            now = 1_000L,
        )
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 2_000L))
        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `down-break fans out as SELL`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "1999.4", 2_000L))
        assertThat(emitted.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `no cross within the arm window emits nothing and drops the latch`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(LatchManagerFixture.compiledLatch("2000.0", "0.50", 300_000L), ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.0", 1_000L + 300_001L))
        assertThat(emitted).isEmpty()
        // a later in-range cross does nothing — the latch is gone
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.6", 1_000L + 300_002L))
        assertThat(emitted).isEmpty()
    }

    @Test
    fun `CLOSE_BEYOND ignores spike tick until bar closes beyond wire`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(
                ref = "2000.0",
                offset = "0.50",
                windowMs = 300_000L,
                confirm = LatchCloseBeyond,
            ),
            ec = ec,
            now = 1_000L,
        )

        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2001.0", 2_000L))
        assertThat(emitted).isEmpty()
        mgr.onCandle(LatchManagerFixture.candle("XAUUSD", "2000.40", 60_000L))
        assertThat(emitted).isEmpty()
        mgr.onCandle(LatchManagerFixture.candle("XAUUSD", "2000.60", 120_000L))

        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `TIME_IN_BREACH resets when price returns inside wires`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(
                ref = "2000.0",
                offset = "0.50",
                windowMs = 300_000L,
                confirm = LatchTimeInBreach(DurationAst(10_000L)),
            ),
            ec = ec,
            now = 1_000L,
        )

        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.60", 2_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.40", 8_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.70", 12_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.80", 21_999L))
        assertThat(emitted).isEmpty()
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.80", 22_000L))
        assertThat(emitted).hasSize(1)
    }

    @Test
    fun `TIME_IN_BREACH hands pending direction to opposite wire`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(
                ref = "2000.0",
                offset = "0.50",
                windowMs = 300_000L,
                confirm = LatchTimeInBreach(DurationAst(10_000L)),
            ),
            ec = ec,
            now = 1_000L,
        )

        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.60", 2_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "1999.40", 6_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "1999.30", 16_000L))

        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.SELL)
    }

    @Test
    fun `ARM window expiry cancels pending confirmation`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(
                ref = "2000.0",
                offset = "0.50",
                windowMs = 5_000L,
                confirm = LatchTimeInBreach(DurationAst(10_000L)),
            ),
            ec = ec,
            now = 1_000L,
        )

        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.60", 2_000L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.70", 6_001L))
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.80", 20_000L))

        assertThat(emitted).isEmpty()
    }

    @Test
    fun `RETEST_HOLD fires when breach retests within band without crossing back`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        mgr.arm(
            LatchManagerFixture.compiledLatch(
                ref = "2000.0",
                offset = "0.50",
                windowMs = 300_000L,
                confirm = LatchRetestHold(NumLit(BigDecimal("0.10")), DurationAst(20_000L)),
            ),
            ec = ec,
            now = 1_000L,
        )

        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.90", 2_000L))
        assertThat(emitted).isEmpty()
        mgr.onTick(LatchManagerFixture.tick("XAUUSD", "2000.55", 10_000L))

        assertThat(emitted).hasSize(1)
        assertThat(emitted.single().side).isEqualTo(Side.BUY)
    }

    @Test
    fun `ENTER ON uses entry stream price snapshot as anchor`() {
        val emitted = mutableListOf<OrderRequest>()
        val mgr = LatchManagerFixture.manager(emit = emitted::add, now = 1_000L)
        val ec = LatchManagerFixture.ec("XAUUSD")
        val compiled =
            CompiledLatch(
                streamAlias = "s",
                reference = CompiledExpr { Value.Num(BigDecimal("2000.00")) },
                offset = CompiledExpr { Value.Num(BigDecimal("0.50")) },
                armWindowMs = 300_000L,
                name = null,
                entries =
                    listOf(
                        CompiledLatchEntry(
                            streamAlias = "silver",
                            builder =
                                LatchEntryBuilder { direction, anchor, ctx ->
                                    OrderRequest.Limit(
                                        id = "silver-entry",
                                        symbol = ctx.streams.getValue("silver").qktSymbol,
                                        side = if (direction > 0) Side.BUY else Side.SELL,
                                        quantity = BigDecimal.ONE,
                                        limitPrice = anchor.subtract(BigDecimal("2.00")),
                                        timeInForce = TimeInForce.GTC,
                                        timestamp = ctx.nowMs(),
                                        strategyId = "test",
                                    )
                                },
                        ),
                    ),
                confirm = LatchCloseBeyond,
            )

        mgr.arm(compiled, ec = ec, now = 1_000L)
        mgr.onTick(LatchManagerFixture.tick("BACKTEST:XAGUSD", "30.00", 10_000L))
        mgr.onCandle(LatchManagerFixture.candle("XAUUSD", "2000.60", 60_000L))

        val order = emitted.single() as OrderRequest.Limit
        assertThat(order.symbol).isEqualTo("BACKTEST:XAGUSD")
        assertThat(order.side).isEqualTo(Side.BUY)
        assertThat(order.limitPrice).isEqualByComparingTo("28.00")
        assertThat(order.timestamp).isEqualTo(60_000L)
    }
}
