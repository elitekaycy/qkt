package com.qkt.dsl.stdlib

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
        val tolerance = org.assertj.core.api.Assertions.within(1e-4)
        assertThat(e).isCloseTo(2.71828, tolerance)
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
