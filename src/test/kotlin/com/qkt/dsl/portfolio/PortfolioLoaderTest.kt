package com.qkt.dsl.portfolio

import java.nio.file.Paths
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class PortfolioLoaderTest {
    @Test
    fun `simple portfolio loads with one compiled child`() {
        val path = Paths.get("src/test/resources/dsl/portfolio_simple.qkt")
        val compiled = PortfolioLoader.load(path)
        assertThat(compiled.children).hasSize(1)
        assertThat(compiled.children[0].alias).isEqualTo("child")
        assertThat(compiled.children[0].hold).isFalse
        assertThat(compiled.children[0].strategyId).isEqualTo("simple:child")
    }

    @Test
    fun `cycle in import graph rejected`() {
        val path = Paths.get("src/test/resources/dsl/portfolio_cycle_a.qkt")
        assertThatThrownBy { PortfolioLoader.load(path) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("cycle")
    }
}
