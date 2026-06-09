package com.qkt.cli

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class ParamGridTest {
    @Test
    fun `single-value flags produce one combo of plain overrides`() {
        val combos = ParamGrid.parse(listOf("fast=5", "slow=20"))
        assertThat(combos).hasSize(1)
        assertThat(combos.single().overrides).containsEntry("fast", "5").containsEntry("slow", "20")
        assertThat(combos.single().label).isEqualTo("fast=5,slow=20")
    }

    @Test
    fun `comma lists form the cartesian product with deterministic labels`() {
        val combos = ParamGrid.parse(listOf("fast=5,10", "slow=20,40"))
        assertThat(combos).hasSize(4)
        assertThat(combos.map { it.label }).containsExactlyInAnyOrder(
            "fast=5,slow=20",
            "fast=5,slow=40",
            "fast=10,slow=20",
            "fast=10,slow=40",
        )
    }

    @Test
    fun `no flags yields a single default combo`() {
        assertThat(ParamGrid.parse(emptyList())).containsExactly(ParamGrid.Combo("default", emptyMap()))
    }

    @Test
    fun `duplicate axis values are de-duplicated`() {
        assertThat(ParamGrid.parse(listOf("fast=5,5,10"))).hasSize(2)
    }

    @Test
    fun `a malformed token fails loudly`() {
        assertThatThrownBy { ParamGrid.parse(listOf("fast")) }.hasMessageContaining("expected NAME=VALUE")
    }
}
