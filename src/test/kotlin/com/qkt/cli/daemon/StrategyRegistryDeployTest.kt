package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryDeployTest : StrategyRegistryFixture() {
    @Test
    fun `deploy adds a handle and list returns it`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        val handle = registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThat(handle.name).isEqualTo("alpha")
        assertThat(registry.list()).hasSize(1)
        assertThat(registry.get("alpha")).isSameAs(handle)
    }

    @Test
    fun `deploy rejects duplicate names`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThatThrownBy { registry.deploy("alpha", tmp.resolve("alpha.qkt")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("already deployed")
    }

    @Test
    fun `deploy rejects invalid names`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        assertThatThrownBy { registry.deploy("bad name!", tmp.resolve("x.qkt")) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("invalid strategy name")
    }

    @Test
    fun `child-style names with slash are accepted`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        val handle = registry.deploy("mybook/trend", tmp.resolve("trend.qkt"))
        assertThat(handle.name).isEqualTo("mybook/trend")
    }

    @Test
    fun `malformed slashed names are rejected`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        for (bad in listOf("/foo", "foo/", "foo//bar", "foo/bar/baz")) {
            assertThatThrownBy { registry.deploy(bad, tmp.resolve("x.qkt")) }
                .isInstanceOf(IllegalArgumentException::class.java)
                .hasMessageContaining("invalid strategy name")
        }
    }
}
