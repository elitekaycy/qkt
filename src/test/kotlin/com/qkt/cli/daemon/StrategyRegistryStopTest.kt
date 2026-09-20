package com.qkt.cli.daemon

import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryStopTest : StrategyRegistryFixture() {
    @Test
    fun `stop removes the handle and closes it`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        val handle = registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        assertThat(registry.stop("alpha")).isTrue
        assertThat(registry.list()).isEmpty()
        assertThat(handle.isRunning()).isFalse
    }

    @Test
    fun `stop returns false for unknown name`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        assertThat(registry.stop("nope")).isFalse
    }

    @Test
    fun `stopAll drains everything`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val registry = StrategyRegistry(fakeFactory(state))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))
        registry.stopAll()
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `stopAll requests every stop before awaiting sessions`(
        @TempDir tmp: Path,
    ) {
        val state = StateDir.resolve(tmp.toString())
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))

        registry.stopAll()

        val firstAwait = events.indexOfFirst { it.startsWith("await:") }
        val lastRequest = events.indexOfLast { it.startsWith("request:") }
        assertThat(firstAwait).isGreaterThan(lastRequest)
    }

    @Test
    fun `stopAll signals every session before any session's blocking stop runs`(
        @TempDir tmp: Path,
    ) {
        // A live session's stop() waits out its drain grace and releases its broker; with many
        // per-strategy sessions that fan-out is sequential, and every session after the first
        // keeps trading until its turn. The non-blocking request must reach all of them first.
        val state = StateDir.resolve(tmp.toString())
        val events = java.util.concurrent.CopyOnWriteArrayList<String>()
        val registry = StrategyRegistry(recordingFactory(state, events))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        registry.deploy("beta", tmp.resolve("beta.qkt"))
        registry.deploy("gamma", tmp.resolve("gamma.qkt"))

        registry.stopAll()

        val requests = events.filter { it.startsWith("request:") }
        assertThat(requests).hasSize(3)
        val lastRequest = events.indexOfLast { it.startsWith("request:") }
        val firstStop = events.indexOfFirst { it.startsWith("stop:") }
        assertThat(firstStop).isGreaterThan(lastRequest)
        // Each session's blocking stop still runs, once, before its termination wait.
        assertThat(events.filter { it.startsWith("stop:") }).hasSize(3)
        for (name in listOf("alpha", "beta", "gamma")) {
            assertThat(events.indexOfFirst { it.startsWith("stop:$name") })
                .isLessThan(events.indexOfFirst { it.startsWith("await:$name") })
        }
    }
}
