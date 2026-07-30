package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.daemon.portfolio.ChildHandle
import com.qkt.cli.daemon.portfolio.PortfolioSupervisor
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.AlwaysRun
import com.qkt.dsl.ast.ImportClause
import com.qkt.dsl.ast.PortfolioAst
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyRegistryTest {
    private val opened = mutableListOf<ObservabilityServer>()

    @AfterEach
    fun cleanup() {
        for (s in opened) runCatching { s.close() }
        opened.clear()
    }

    private fun fakeFactory(stateDir: StateDir): StrategyHandle.Factory =
        StrategyHandle.Factory { name, _, _ ->
            val ring = EventRing(capacity = 16)
            val running = AtomicBoolean(true)
            val live =
                object : LiveSessionHandle {
                    override val running: Boolean get() = running.get()
                    override val droppedTicks: Long = 0L

                    override fun stop() {
                        running.set(false)
                    }

                    override fun awaitTermination(timeout: Duration): Boolean = true

                    override fun recentTrades(): List<Trade> = emptyList()

                    override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                        emptyList()

                    override fun flatten() = Unit
                }
            val server =
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        StatusSnapshot(
                            strategy = name,
                            version = 1,
                            uptimeMs = 0L,
                            startedAt = Instant.EPOCH.toString(),
                            equity = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                            realized = BigDecimal.ZERO,
                            unrealized = BigDecimal.ZERO,
                            positions = emptyList<PositionDto>(),
                            lastTrade = null,
                        )
                    },
                    running = { running.get() },
                    onStop = { running.set(false) },
                    bind = "127.0.0.1",
                    port = 0,
                ).also { it.start() }
            opened.add(server)
            val ast =
                StrategyAst(
                    name = name,
                    version = 1,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                )
            StrategyHandle(
                name = name,
                ast = ast,
                live = live,
                observability = server,
                ring = ring,
                logFile = stateDir.logFile(name),
                startedAt = Instant.now(),
            )
        }

    private fun recordingFactory(
        stateDir: StateDir,
        events: MutableList<String>,
    ): StrategyHandle.Factory =
        StrategyHandle.Factory { name, file, _ ->
            if (file.fileName.toString().contains("bad")) error("replacement failed")
            val ring = EventRing(capacity = 16)
            val running = AtomicBoolean(true)
            val live =
                object : LiveSessionHandle {
                    override val running: Boolean get() = running.get()
                    override val droppedTicks: Long = 0L

                    override fun stop() {
                        events.add("stop:$name:${file.fileName}")
                        running.set(false)
                    }

                    override fun awaitTermination(timeout: Duration): Boolean {
                        events.add("await:$name:${file.fileName}")
                        return true
                    }

                    override fun recentTrades(): List<Trade> = emptyList()

                    override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                        emptyList()

                    override fun halt(reason: String) {
                        events.add("halt:$name:${file.fileName}:$reason")
                    }

                    override fun resume() {
                        events.add("resume:$name:${file.fileName}")
                    }

                    override fun flatten() = Unit
                }
            val server =
                ObservabilityServer(
                    ring = ring,
                    statusProvider = {
                        StatusSnapshot(
                            strategy = name,
                            version = 1,
                            uptimeMs = 0L,
                            startedAt = Instant.EPOCH.toString(),
                            equity = BigDecimal.ZERO,
                            balance = BigDecimal.ZERO,
                            realized = BigDecimal.ZERO,
                            unrealized = BigDecimal.ZERO,
                            positions = emptyList<PositionDto>(),
                            lastTrade = null,
                        )
                    },
                    running = { running.get() },
                    onStop = { running.set(false) },
                    bind = "127.0.0.1",
                    port = 0,
                ).also { it.start() }
            opened.add(server)
            val ast =
                StrategyAst(
                    name = name,
                    version = 1,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                )
            StrategyHandle(
                name = name,
                ast = ast,
                live = live,
                observability = server,
                ring = ring,
                logFile = stateDir.logFile(name),
                startedAt = Instant.now(),
                sourceFile = file,
            )
        }

    private fun portfolioRecord(
        stateDir: StateDir,
        events: MutableList<String>,
        name: String,
        childAlias: String,
        version: Int,
    ): PortfolioRecord {
        val child = childHandle(stateDir, events, name, childAlias, version)
        val childMeta = child.childMeta ?: error("missing child metadata")
        val wrapper =
            ChildHandle(
                parent = name,
                alias = childAlias,
                hold = false,
                handle = child,
                gateActive = childMeta.gateActive,
                operatorStop = childMeta.operatorStop,
            )
        val supervisor =
            PortfolioSupervisor(
                ast =
                    PortfolioAst(
                        name = name,
                        version = version,
                        streams = emptyList(),
                        imports = listOf(ImportClause("$childAlias.qkt", childAlias)),
                        rules = listOf(AlwaysRun(childAlias)),
                    ),
                children = listOf(wrapper),
                marketSource = null,
            ).also { it.start() }
        return PortfolioRecord(
            name = name,
            version = version,
            supervisor = supervisor,
            children = listOf(child),
            logFile = stateDir.logFile(name),
            startedAt = Instant.now(),
        )
    }

    private fun childHandle(
        stateDir: StateDir,
        events: MutableList<String>,
        parent: String,
        alias: String,
        version: Int,
    ): StrategyHandle {
        val name = "$parent/$alias"
        val ring = EventRing(capacity = 16)
        val running = AtomicBoolean(true)
        val live =
            object : LiveSessionHandle {
                override val running: Boolean get() = running.get()
                override val droppedTicks: Long = 0L

                override fun stop() {
                    events.add("stop:$name:v$version")
                    running.set(false)
                }

                override fun awaitTermination(timeout: Duration): Boolean = true

                override fun recentTrades(): List<Trade> = emptyList()

                override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> =
                    emptyList()

                override fun halt(reason: String) {
                    events.add("halt:$name:v$version:$reason")
                }

                override fun resume() {
                    events.add("resume:$name:v$version")
                }

                override fun flatten() = Unit
            }
        val server =
            ObservabilityServer(
                ring = ring,
                statusProvider = {
                    StatusSnapshot(
                        strategy = name,
                        version = version,
                        uptimeMs = 0L,
                        startedAt = Instant.EPOCH.toString(),
                        equity = BigDecimal.ZERO,
                        balance = BigDecimal.ZERO,
                        realized = BigDecimal.ZERO,
                        unrealized = BigDecimal.ZERO,
                        positions = emptyList<PositionDto>(),
                        lastTrade = null,
                    )
                },
                running = { running.get() },
                onStop = { running.set(false) },
                bind = "127.0.0.1",
                port = 0,
            ).also { it.start() }
        opened.add(server)
        val meta =
            StrategyHandle.ChildMeta(
                parent = parent,
                alias = alias,
                hold = false,
                gateActive = AtomicBoolean(true),
                operatorStop = AtomicBoolean(false),
            )
        return StrategyHandle(
            name = name,
            ast =
                StrategyAst(
                    name = name,
                    version = version,
                    streams =
                        listOf(
                            StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m"),
                        ),
                    constants = emptyList(),
                    lets = emptyList(),
                    defaults = null,
                    rules = emptyList<WhenThen>(),
                ),
            live = live,
            observability = server,
            ring = ring,
            logFile = stateDir.logFile(name),
            startedAt = Instant.now(),
            childMeta = meta,
        )
    }

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
        val lastStop = events.indexOfLast { it.startsWith("stop:") }
        assertThat(firstAwait).isGreaterThan(lastStop)
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
        assertThat(events).contains("halt:alpha:alpha-v1.qkt:operator resync")
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
        assertThat(events).contains("halt:alpha:alpha-v1.qkt:operator resync")
        assertThat(events).contains("stop:alpha:alpha-v1.qkt")
        assertThat(events).doesNotContain("resume:alpha:alpha-v1.qkt")
    }

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
        assertThat(events).contains("halt:book/trend:v1:operator resync")
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
        assertThat(events).doesNotContain("halt:book/trend:v1:operator resync")
        assertThat(events).contains("stop:book/meanrev:v2")
    }
}
