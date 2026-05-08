package com.qkt.dsl.kotlin

import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.ast.StackDirection
import com.qkt.dsl.ast.StackEntryRef
import com.qkt.dsl.ast.StackLayers
import com.qkt.dsl.ast.StackSpacing
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class StackBuilderTest {
    @Test
    fun `stack count and spacing builds StackSpacing`() {
        val s = stack(count = 3, spacing = NumLit(BigDecimal("100")))
        assertThat(s).isInstanceOf(StackSpacing::class.java)
        s as StackSpacing
        assertThat(s.count).isEqualTo(3)
        assertThat(s.direction).isEqualTo(StackDirection.TRADE_DIRECTION)
    }

    @Test
    fun `stackOf builds StackLayers and rejects empty list`() {
        val l1 = layer(qty = NumLit(BigDecimal("0.1")))
        val l2 = layer(qty = NumLit(BigDecimal("0.2")), at = entryPrice + NumLit(BigDecimal("100")))
        val sl = stackOf(l1, l2)
        assertThat(sl).isInstanceOf(StackLayers::class.java)
        assertThatThrownBy { stackOf() }.isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `entryPrice resolves to StackEntryRef`() {
        assertThat(entryPrice).isEqualTo(StackEntryRef)
    }

    @Test
    fun `duration parses suffix-style strings`() {
        assertThat(duration("1h").millis).isEqualTo(3_600_000L)
        assertThat(duration("30m").millis).isEqualTo(1_800_000L)
        assertThat(duration("15s").millis).isEqualTo(15_000L)
        assertThat(duration("2d").millis).isEqualTo(172_800_000L)
    }

    @Test
    fun `layer 2 without AT is rejected by stackOf`() {
        val l1 = layer(qty = NumLit(BigDecimal("0.1")))
        val l2 = layer(qty = NumLit(BigDecimal("0.2"))) // no at
        assertThatThrownBy { stackOf(l1, l2) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("AT")
    }
}
