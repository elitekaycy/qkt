package com.qkt.cli.daemon

import com.qkt.app.LiveSessionHandle
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.PositionDto
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.StrategyAst
import com.qkt.dsl.ast.StreamDecl
import com.qkt.dsl.ast.WhenThen
import com.qkt.execution.Trade
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DaemonControlTest {
    private val opened = mutableListOf<AutoCloseable>()

    @AfterEach
    fun cleanup() {
        for (c in opened.reversed()) runCatching { c.close() }
        opened.clear()
    }

    private class RecordingHandle(
        initialRunning: Boolean = true,
        private var halted: Boolean = false,
    ) : LiveSessionHandle {
        val halts = mutableListOf<String>()
        var resumes = 0
        private val alive = AtomicBoolean(initialRunning)

        override val running: Boolean get() = alive.get()
        override val droppedTicks: Long = 0L

        override fun stop() {
            alive.set(false)
        }

        override fun awaitTermination(timeout: Duration): Boolean = true

        override fun recentTrades(): List<Trade> = emptyList()

        override fun pendingStackLayerInfos(): List<com.qkt.app.OrderManager.PendingStackLayerInfo> = emptyList()

        override fun flatten() = Unit

        override fun halt(reason: String) {
            halts.add(reason)
            halted = true
        }

        override fun resume() {
            resumes++
            halted = false
        }

        override fun isHalted(): Boolean = halted
    }

    private fun stubAst(name: String): StrategyAst =
        StrategyAst(
            name = name,
            version = 1,
            streams = listOf(StreamDecl(alias = "s", broker = "BACKTEST", symbol = "BTCUSDT", timeframe = "1m")),
            constants = emptyList(),
            lets = emptyList(),
            defaults = null,
            rules = emptyList<WhenThen>(),
        )

    private fun registryWith(
        tmp: Path,
        names: List<String>,
    ): Pair<StrategyRegistry, Map<String, RecordingHandle>> {
        val handlesByName = names.associateWith { RecordingHandle() }
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    val ring = EventRing(capacity = 16)
                    val live = handlesByName.getValue(name)
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
                            running = { live.running },
                            onStop = { live.stop() },
                            bind = "127.0.0.1",
                            port = 0,
                        ).also { it.start() }
                    opened.add(server)
                    StrategyHandle(
                        name = name,
                        ast = stubAst(name),
                        live = live,
                        observability = server,
                        ring = ring,
                        logFile = tmp.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        names.forEach { registry.deploy(it, tmp.resolve("$it.qkt")) }
        return registry to handlesByName
    }

    @Test
    fun `halt All halts every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(result.unknown).isEmpty()
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).hasSize(1)
    }

    @Test
    fun `halt one strategy halts only it`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("alpha"))
        assertThat(result.affected).containsExactly("alpha")
        assertThat(handles.getValue("alpha").halts).hasSize(1)
        assertThat(handles.getValue("beta").halts).isEmpty()
    }

    @Test
    fun `operator halt and resume are journaled`(
        @TempDir tmp: Path,
    ) {
        val (registry, _) = registryWith(tmp, listOf("alpha"))
        val stateDir = StateDir.resolve(tmp.resolve("qkt-state").toString())
        val control = RegistryDaemonControl(registry, OperatorJournal(stateDir, "test"))

        control.halt(Target.Strategy("alpha"))
        control.resume(Target.Strategy("alpha"))

        val journal =
            Files
                .walk(stateDir.stateRoot.resolve("journal/alpha"))
                .filter { Files.isRegularFile(it) }
                .findFirst()
                .orElseThrow()
                .let(Files::readString)
        assertThat(journal).contains("\"kind\":\"operator_action\"")
        assertThat(journal).contains("\"action\":\"halt\"")
        assertThat(journal).contains("\"action\":\"resume\"")
        assertThat(journal).contains("\"target\":\"alpha\"")
        assertThat(journal).contains("\"source\":\"test\"")
        assertThat(journal).contains("\"outcome\":\"accepted\"")
    }

    @Test
    fun `halt unknown name reports it and changes nothing`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha"))
        val result = RegistryDaemonControl(registry).halt(Target.Strategy("ghost"))
        assertThat(result.affected).isEmpty()
        assertThat(result.unknown).containsExactly("ghost")
        assertThat(handles.getValue("alpha").halts).isEmpty()
    }

    @Test
    fun `resume All resumes every strategy`(
        @TempDir tmp: Path,
    ) {
        val (registry, handles) = registryWith(tmp, listOf("alpha", "beta"))
        val result = RegistryDaemonControl(registry).resume(Target.All)
        assertThat(result.affected).containsExactlyInAnyOrder("alpha", "beta")
        assertThat(handles.getValue("alpha").resumes).isEqualTo(1)
        assertThat(handles.getValue("beta").resumes).isEqualTo(1)
    }

    @Test
    fun `status returns one StrategyStatus per deployed strategy`(
        @TempDir tmp: Path,
    ) {
        val names = listOf("alpha", "beta")
        val (registry, handles) = registryWith(tmp, names)
        // alpha: running, not halted; beta: running, halted
        handles.getValue("beta").halt("operator")
        val report = RegistryDaemonControl(registry).status()
        assertThat(report.strategies).hasSize(2)
        val byName = report.strategies.associateBy { it.name }
        assertThat(byName.getValue("alpha").running).isTrue()
        assertThat(byName.getValue("alpha").halted).isFalse()
        assertThat(byName.getValue("beta").running).isTrue()
        assertThat(byName.getValue("beta").halted).isTrue()
    }

    @Test
    fun `status on empty registry returns empty report`(
        @TempDir tmp: Path,
    ) {
        val (registry, _) = registryWith(tmp, emptyList())
        val report = RegistryDaemonControl(registry).status()
        assertThat(report.strategies).isEmpty()
    }
}
