package com.qkt.dsl.stdlib

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class FuncRegistryTest {
    @Test
    fun `ABS returns absolute value`() {
        assertThat(FuncRegistry.invoke("ABS", listOf(BigDecimal("-3.14"))))
            .isEqualByComparingTo("3.14")
    }

    @Test
    fun `SQRT returns positive root`() {
        assertThat(FuncRegistry.invoke("SQRT", listOf(BigDecimal("16"))))
            .isEqualByComparingTo("4")
    }

    @Test
    fun `LOG returns natural log`() {
        assertThat(FuncRegistry.invoke("LOG", listOf(BigDecimal("1"))))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `EXP returns e^x`() {
        assertThat(FuncRegistry.invoke("EXP", listOf(BigDecimal.ZERO)))
            .isEqualByComparingTo("1")
        val e = FuncRegistry.invoke("EXP", listOf(BigDecimal.ONE))!!.toDouble()
        assertThat(e).isCloseTo(2.71828, within(1e-4))
    }

    @Test
    fun `POW returns base^exponent`() {
        assertThat(FuncRegistry.invoke("POW", listOf(BigDecimal("2"), BigDecimal("10"))))
            .isEqualByComparingTo("1024")
        assertThat(FuncRegistry.invoke("POW", listOf(BigDecimal("9"), BigDecimal("0.5"))))
            .isEqualByComparingTo("3")
    }

    @Test
    fun `SQRT of negative returns null (domain error)`() {
        assertThat(FuncRegistry.invoke("SQRT", listOf(BigDecimal("-1")))).isNull()
    }

    @Test
    fun `LOG of zero or negative returns null (domain error)`() {
        assertThat(FuncRegistry.invoke("LOG", listOf(BigDecimal.ZERO))).isNull()
        assertThat(FuncRegistry.invoke("LOG", listOf(BigDecimal("-1")))).isNull()
    }

    @Test
    fun `EXP overflow returns null`() {
        // exp(10000) overflows double range — return null instead of Infinity.
        assertThat(FuncRegistry.invoke("EXP", listOf(BigDecimal("10000")))).isNull()
    }

    @Test
    fun `POW rejects wrong arity`() {
        assertThatThrownBy { FuncRegistry.invoke("POW", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MIN returns the smallest`() {
        assertThat(FuncRegistry.invoke("MIN", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"))))
            .isEqualByComparingTo("1")
    }

    @Test
    fun `MAX returns the largest`() {
        assertThat(FuncRegistry.invoke("MAX", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2"))))
            .isEqualByComparingTo("3")
    }

    @Test
    fun `RANK_OF returns the 1-based cross-sectional rank of the first arg, 1 = highest`() {
        assertThat(FuncRegistry.invoke("RANK_OF", listOf(BigDecimal("5"), BigDecimal("3"), BigDecimal("1"))))
            .isEqualByComparingTo("1") // first is the highest
        assertThat(FuncRegistry.invoke("RANK_OF", listOf(BigDecimal("3"), BigDecimal("5"), BigDecimal("1"))))
            .isEqualByComparingTo("2") // one value above it
        assertThat(FuncRegistry.invoke("RANK_OF", listOf(BigDecimal("1"), BigDecimal("5"), BigDecimal("3"))))
            .isEqualByComparingTo("3") // two values above it
    }

    @Test
    fun `RANK_OF ties share the top rank (competition ranking)`() {
        assertThat(FuncRegistry.invoke("RANK_OF", listOf(BigDecimal("5"), BigDecimal("5"), BigDecimal("1"))))
            .isEqualByComparingTo("1") // tied for highest -> rank 1
        assertThat(FuncRegistry.invoke("RANK_OF", listOf(BigDecimal("1"), BigDecimal("5"), BigDecimal("5"))))
            .isEqualByComparingTo("3") // two strictly above -> rank 3
    }

    @Test
    fun `RANK_OF rejects single arg`() {
        assertThatThrownBy { FuncRegistry.invoke("RANK_OF", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MOD returns floored modulo with the sign of the divisor`() {
        assertThat(FuncRegistry.invoke("MOD", listOf(BigDecimal("7"), BigDecimal("3"))))
            .isEqualByComparingTo("1")
        // Grid distance: how far past the nearest 0.0050 figure.
        assertThat(FuncRegistry.invoke("MOD", listOf(BigDecimal("1.2034"), BigDecimal("0.0050"))))
            .isEqualByComparingTo("0.0034")
        // Floored modulo: result carries the divisor's sign, not the dividend's.
        assertThat(FuncRegistry.invoke("MOD", listOf(BigDecimal("-7"), BigDecimal("3"))))
            .isEqualByComparingTo("2")
        assertThat(FuncRegistry.invoke("MOD", listOf(BigDecimal("7"), BigDecimal("-3"))))
            .isEqualByComparingTo("-2")
    }

    @Test
    fun `MOD by zero returns null (domain error)`() {
        assertThat(FuncRegistry.invoke("MOD", listOf(BigDecimal("7"), BigDecimal.ZERO))).isNull()
    }

    @Test
    fun `FLOOR rounds toward negative infinity`() {
        assertThat(FuncRegistry.invoke("FLOOR", listOf(BigDecimal("3.7")))).isEqualByComparingTo("3")
        assertThat(FuncRegistry.invoke("FLOOR", listOf(BigDecimal("-1.2")))).isEqualByComparingTo("-2")
    }

    @Test
    fun `CEIL rounds toward positive infinity`() {
        assertThat(FuncRegistry.invoke("CEIL", listOf(BigDecimal("3.2")))).isEqualByComparingTo("4")
        assertThat(FuncRegistry.invoke("CEIL", listOf(BigDecimal("-1.2")))).isEqualByComparingTo("-1")
    }

    @Test
    fun `ROUND rounds half to even (Money convention)`() {
        assertThat(FuncRegistry.invoke("ROUND", listOf(BigDecimal("2.4")))).isEqualByComparingTo("2")
        assertThat(FuncRegistry.invoke("ROUND", listOf(BigDecimal("2.6")))).isEqualByComparingTo("3")
        assertThat(FuncRegistry.invoke("ROUND", listOf(BigDecimal("2.5")))).isEqualByComparingTo("2")
        assertThat(FuncRegistry.invoke("ROUND", listOf(BigDecimal("3.5")))).isEqualByComparingTo("4")
    }

    @Test
    fun `ROUND_TO snaps to the nearest multiple of step`() {
        assertThat(FuncRegistry.invoke("ROUND_TO", listOf(BigDecimal("2347"), BigDecimal("25"))))
            .isEqualByComparingTo("2350")
        assertThat(FuncRegistry.invoke("ROUND_TO", listOf(BigDecimal("31.27"), BigDecimal("0.5"))))
            .isEqualByComparingTo("31.5")
        assertThat(FuncRegistry.invoke("ROUND_TO", listOf(BigDecimal("13"), BigDecimal("5"))))
            .isEqualByComparingTo("15")
    }

    @Test
    fun `ROUND_TO by zero step returns null (domain error)`() {
        assertThat(FuncRegistry.invoke("ROUND_TO", listOf(BigDecimal("10"), BigDecimal.ZERO))).isNull()
    }

    @Test
    fun `MOD rejects wrong arity`() {
        assertThatThrownBy { FuncRegistry.invoke("MOD", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ROUND rejects wrong arity`() {
        assertThatThrownBy { FuncRegistry.invoke("ROUND", listOf(BigDecimal.ONE, BigDecimal.TWO)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `ABS rejects wrong arity`() {
        assertThatThrownBy { FuncRegistry.invoke("ABS", emptyList()) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `MIN rejects single arg`() {
        assertThatThrownBy { FuncRegistry.invoke("MIN", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `unknown function throws`() {
        assertThatThrownBy { FuncRegistry.invoke("UNKNOWN", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `has reports membership`() {
        assertThat(FuncRegistry.has("ABS")).isTrue()
        assertThat(FuncRegistry.has("UNKNOWN")).isFalse()
    }
}
