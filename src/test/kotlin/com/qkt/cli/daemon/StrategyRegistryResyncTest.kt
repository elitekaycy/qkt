package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryResyncTest : StrategyRegistryFixture() {
    @Test
    fun `resync replaces a standalone strategy and closes the old handle`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val events = mutableListOf<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        val old = registry.deploy("alpha", tmp.resolve("alpha-v1.qkt"))

        val replacement = registry.resyncStrategy("alpha", tmp.resolve("alpha-v2.qkt"))

        assertThat(replacement).isNotSameAs(old)
        assertThat(registry.get("alpha")).isSameAs(replacement)
        assertThat(old.isRunning()).isFalse()
        assertThat(replacement.isRunning()).isTrue()
        assertThat(events).contains("halt:alpha:alpha-v1.qkt:operator resync:TRANSIENT")
        assertThat(events).contains("stop:alpha:alpha-v1.qkt")
    }

    @Test
    fun `resync drains the old strategy before replacement creation`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val events = mutableListOf<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        val old = registry.deploy("alpha", tmp.resolve("alpha-v1.qkt"))

        assertThatThrownBy { registry.resyncStrategy("alpha", tmp.resolve("bad-v2.qkt")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("replacement failed")

        assertThat(registry.get("alpha")).isNull()
        assertThat(old.isRunning()).isFalse()
        assertThat(events).contains("halt:alpha:alpha-v1.qkt:operator resync:TRANSIENT")
        assertThat(events).contains("stop:alpha:alpha-v1.qkt")
        assertThat(events).doesNotContain("resume:alpha:alpha-v1.qkt")
    }
}
