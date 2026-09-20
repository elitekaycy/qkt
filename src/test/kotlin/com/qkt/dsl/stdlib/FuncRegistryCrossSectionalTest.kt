package com.qkt.dsl.stdlib

import java.math.BigDecimal
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.assertj.core.api.Assertions.within
import org.junit.jupiter.api.Test

class FuncRegistryCrossSectionalTest {
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
    fun `NORMALIZE returns min-max scaled score of first arg`() {
        assertThat(FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("5"))))
            .isEqualByComparingTo("0.5")
        assertThat(FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal("1"), BigDecimal("1"), BigDecimal("5"))))
            .isEqualByComparingTo("0")
        assertThat(FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal("5"), BigDecimal("1"), BigDecimal("5"))))
            .isEqualByComparingTo("1")
    }

    @Test
    fun `NORMALIZE returns 0 when all values are equal`() {
        assertThat(FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal("7"), BigDecimal("7"), BigDecimal("7"))))
            .isEqualByComparingTo("0")
    }

    @Test
    fun `NORMALIZE works with negative values`() {
        assertThat(FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal("-1"), BigDecimal("-3"), BigDecimal("1"))))
            .isEqualByComparingTo("0.5")
    }

    @Test
    fun `SOFTMAX returns probability weight of first arg summing symmetrically to 1`() {
        val a = FuncRegistry.invoke("SOFTMAX", listOf(BigDecimal("1"), BigDecimal("2"), BigDecimal("3")))!!.toDouble()
        val b = FuncRegistry.invoke("SOFTMAX", listOf(BigDecimal("2"), BigDecimal("1"), BigDecimal("3")))!!.toDouble()
        val c = FuncRegistry.invoke("SOFTMAX", listOf(BigDecimal("3"), BigDecimal("1"), BigDecimal("2")))!!.toDouble()
        assertThat(a).isCloseTo(0.0900306, within(1e-6))
        assertThat(b).isCloseTo(0.2447285, within(1e-6))
        assertThat(c).isCloseTo(0.6652409, within(1e-6))
        assertThat(a + b + c).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `SOFTMAX returns equal weights when all values are equal`() {
        val w = FuncRegistry.invoke("SOFTMAX", listOf(BigDecimal("5"), BigDecimal("5"), BigDecimal("5")))!!.toDouble()
        assertThat(w).isCloseTo(1.0 / 3.0, within(1e-10))
    }

    @Test
    fun `SOFTMAX is numerically stable for large inputs`() {
        val a =
            FuncRegistry
                .invoke(
                    "SOFTMAX",
                    listOf(BigDecimal("1000"), BigDecimal("1001"), BigDecimal("1002")),
                )!!
                .toDouble()
        val b =
            FuncRegistry
                .invoke(
                    "SOFTMAX",
                    listOf(BigDecimal("1001"), BigDecimal("1000"), BigDecimal("1002")),
                )!!
                .toDouble()
        val c =
            FuncRegistry
                .invoke(
                    "SOFTMAX",
                    listOf(BigDecimal("1002"), BigDecimal("1000"), BigDecimal("1001")),
                )!!
                .toDouble()
        assertThat(a).isCloseTo(0.0900306, within(1e-6))
        assertThat(b).isCloseTo(0.2447285, within(1e-6))
        assertThat(c).isCloseTo(0.6652409, within(1e-6))
        assertThat(a + b + c).isCloseTo(1.0, within(1e-9))
    }

    @Test
    fun `SOFTMAX rejects single arg`() {
        assertThatThrownBy { FuncRegistry.invoke("SOFTMAX", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `NORMALIZE rejects single arg`() {
        assertThatThrownBy { FuncRegistry.invoke("NORMALIZE", listOf(BigDecimal.ONE)) }
            .isInstanceOf(IllegalArgumentException::class.java)
    }
}
