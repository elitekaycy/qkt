package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryPortfolioResyncTest : StrategyRegistryPortfolioFixture() {
    @Test
    fun `portfolio resync replaces children and closes the old portfolio handles`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val events = mutableListOf<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        val old = portfolioRecord(state, events, name = "book", childAlias = "trend", version = 1)
        val replacement = portfolioRecord(state, events, name = "book", childAlias = "trend", version = 2)
        registry.registerPortfolio(old)

        val result = registry.resyncPortfolio(replacement)

        assertThat(result).isSameAs(replacement)
        assertThat(registry.getPortfolio("book")).isSameAs(replacement)
        assertThat(registry.get("book/trend")).isSameAs(replacement.children.single())
        assertThat(old.children.single().isRunning()).isFalse()
        assertThat(replacement.children.single().isRunning()).isTrue()
        assertThat(events).contains("halt:book/trend:v1:operator resync:TRANSIENT")
        assertThat(events).contains("stop:book/trend:v1")
    }

    @Test
    fun `portfolio resync conflict keeps the old portfolio and closes the replacement`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val events = mutableListOf<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        val old = portfolioRecord(state, events, name = "book", childAlias = "trend", version = 1)
        val replacement = portfolioRecord(state, events, name = "book", childAlias = "meanrev", version = 2)
        val external = registry.deploy("book/meanrev", tmp.resolve("external.qkt"))
        registry.registerPortfolio(old)

        assertThatThrownBy { registry.resyncPortfolio(replacement) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("child name 'book/meanrev' already in use")

        assertThat(registry.getPortfolio("book")).isSameAs(old)
        assertThat(registry.get("book/trend")).isSameAs(old.children.single())
        assertThat(registry.get("book/meanrev")).isSameAs(external)
        assertThat(old.children.single().isRunning()).isTrue()
        assertThat(replacement.children.single().isRunning()).isFalse()
        assertThat(events).doesNotContain("halt:book/trend:v1:operator resync:TRANSIENT")
        assertThat(events).contains("stop:book/meanrev:v2")
    }
}
