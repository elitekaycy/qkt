package com.qkt.dsl.compile

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PendingStacksTest {
    private fun tier(
        threshold: String = "0.005",
        qty: String = "0.05",
    ) = CompiledStackTier(
        mfeThreshold = BigDecimal(threshold),
        withinMs = 30 * 60 * 1000L,
        resolveStackQuantity = { _ -> BigDecimal(qty) },
        slDistance = BigDecimal("0.005"),
        tpDistance = BigDecimal("0.020"),
    )

    @Test
    fun `register then consume returns the same entry`() {
        val stacks = PendingStacks()
        val entry =
            PendingStack(
                parentClientOrderId = "ord-1",
                symbol = "BACKTEST:EURUSD",
                side = Side.BUY,
                tiers = listOf(tier()),
            )
        stacks.register(entry)
        assertThat(stacks.size()).isEqualTo(1)
        assertThat(stacks.contains("ord-1")).isTrue
        val consumed = stacks.consume("ord-1")
        assertThat(consumed).isEqualTo(entry)
        assertThat(stacks.size()).isEqualTo(0)
        assertThat(stacks.contains("ord-1")).isFalse
    }

    @Test
    fun `consume of an unknown id returns null`() {
        val stacks = PendingStacks()
        assertThat(stacks.consume("missing")).isNull()
    }

    @Test
    fun `register rejects duplicate clientOrderId`() {
        val stacks = PendingStacks()
        val entry =
            PendingStack(
                parentClientOrderId = "ord-1",
                symbol = "BACKTEST:EURUSD",
                side = Side.BUY,
                tiers = listOf(tier()),
            )
        stacks.register(entry)
        assertThatThrownBy { stacks.register(entry) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("duplicate registration")
    }

    @Test
    fun `multiple distinct ids coexist`() {
        val stacks = PendingStacks()
        stacks.register(PendingStack("a", "BACKTEST:EURUSD", Side.BUY, listOf(tier())))
        stacks.register(PendingStack("b", "GBPUSD", Side.SELL, listOf(tier())))
        assertThat(stacks.size()).isEqualTo(2)
        assertThat(stacks.consume("a")?.symbol).isEqualTo("BACKTEST:EURUSD")
        assertThat(stacks.consume("b")?.side).isEqualTo(Side.SELL)
    }
}
