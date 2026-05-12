package com.qkt.positions

import com.qkt.common.Side
import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class MfeTrackerTest {
    @Test
    fun `BUY leg starts at zero before any tick`() {
        val t = MfeTracker(Side.BUY, BigDecimal("1.10"))
        assertThat(t.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `BUY favorable move updates MFE`() {
        val t = MfeTracker(Side.BUY, BigDecimal("1.10"))
        t.onTick(BigDecimal("1.11"))
        assertThat(t.value()).isEqualByComparingTo("0.01")
    }

    @Test
    fun `BUY adverse move does not update MFE`() {
        val t = MfeTracker(Side.BUY, BigDecimal("1.10"))
        t.onTick(BigDecimal("1.05"))
        assertThat(t.value()).isEqualByComparingTo("0")
    }

    @Test
    fun `BUY MFE never decreases on subsequent ticks`() {
        val t = MfeTracker(Side.BUY, BigDecimal("1.10"))
        t.onTick(BigDecimal("1.15")) // MFE = 0.05
        t.onTick(BigDecimal("1.12")) // MFE stays 0.05
        assertThat(t.value()).isEqualByComparingTo("0.05")
        t.onTick(BigDecimal("1.20")) // MFE rises to 0.10
        assertThat(t.value()).isEqualByComparingTo("0.10")
        t.onTick(BigDecimal("1.05")) // MFE stays 0.10
        assertThat(t.value()).isEqualByComparingTo("0.10")
    }

    @Test
    fun `SELL leg measures excursion in the opposite direction`() {
        val t = MfeTracker(Side.SELL, BigDecimal("1.10"))
        t.onTick(BigDecimal("1.05"))
        assertThat(t.value()).isEqualByComparingTo("0.05")
        t.onTick(BigDecimal("1.15")) // adverse for SELL
        assertThat(t.value()).isEqualByComparingTo("0.05")
        t.onTick(BigDecimal("1.00"))
        assertThat(t.value()).isEqualByComparingTo("0.10")
    }

    @Test
    fun `entryPrice must be positive`() {
        assertThatThrownBy { MfeTracker(Side.BUY, BigDecimal.ZERO) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("entryPrice")
        assertThatThrownBy { MfeTracker(Side.SELL, BigDecimal("-1.10")) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
