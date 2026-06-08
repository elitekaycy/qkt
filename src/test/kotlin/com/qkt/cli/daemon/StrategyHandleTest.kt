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
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import com.qkt.notify.NotificationEvent
import com.qkt.notify.Notifier
import com.qkt.notify.NotifyEventKind
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyHandleTest {
    private class BoundedFeed(
        private val ticks: List<Tick>,
    ) : TickFeed {
        private val idx = AtomicInteger(0)
        private val gate = CountDownLatch(1)

        override fun next(): Tick? {
            val i = idx.getAndIncrement()
            if (i < ticks.size) return ticks[i]
            gate.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() {
            gate.countDown()
        }
    }

    private class FakeSource(
        private val ticks: List<Tick>,
    ) : MarketSource {
        override val name: String = "Fake"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed = BoundedFeed(ticks)
    }

    @Test
    fun `RealFactory builds a handle with non-zero port and a log file`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val ticks =
            (0 until 3).map {
                Tick(
                    symbol = "BACKTEST:BTCUSDT",
                    price = BigDecimal("42000.0").add(BigDecimal(it * 10)),
                    timestamp = 1_705_276_800_000L + it * 60_000L,
                )
            }
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(ticks) },
            )
        val file = Path.of("src/test/resources/cli/valid_strategy.qkt")
        val handle = factory.create("alpha", file, false)
        try {
            assertThat(handle.name).isEqualTo("alpha")
            assertThat(handle.port).isGreaterThan(0)
            assertThat(handle.isRunning()).isTrue
            assertThat(Files.exists(handle.logFile)).isTrue
            assertThat(handle.logFile).isEqualTo(stateDir.logFile("alpha"))

            // The observability /status endpoint reflects this strategy's name.
            val client = OkHttpClient()
            val resp =
                client
                    .newCall(Request.Builder().url("http://127.0.0.1:${handle.port}/status").build())
                    .execute()
            assertThat(resp.code).isEqualTo(200)
            val body = resp.body!!.string()
            assertThat(body).contains("\"strategy\":\"example\"")
        } finally {
            handle.close()
        }
    }

    private class CapturingNotifier : Notifier {
        val events = java.util.concurrent.CopyOnWriteArrayList<NotificationEvent>()

        override fun notify(event: NotificationEvent) {
            events.add(event)
        }

        override fun close() {}
    }

    @Test
    fun `RealFactory wires the notifier into the session so strategy-started fires`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val notifier = CapturingNotifier()
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(emptyList()) },
                notifier = notifier,
                notifyEvents = setOf(NotifyEventKind.STRATEGY_STARTED),
            )
        val file = Path.of("src/test/resources/cli/valid_strategy.qkt")
        val handle = factory.create("alpha", file, false)
        try {
            assertThat(notifier.events).anyMatch { it is NotificationEvent.StrategyStarted }
        } finally {
            handle.close()
        }
    }

    @Test
    fun `RealFactory raises a clear error on parse failure`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val factory =
            StrategyHandle.RealFactory(
                stateDir = stateDir,
                marketSourceProvider = { FakeSource(emptyList()) },
            )
        val broken = tmp.resolve("broken.qkt").also { Files.writeString(it, "STRATEGY") }
        val err = runCatching { factory.create("bad", broken, false) }.exceptionOrNull()
        assertThat(err).isNotNull
        assertThat(err!!.message).contains("parse failure")
    }

    @Test
    fun `tradeCount counts fills not signals`(
        @TempDir tmp: Path,
    ) {
        val ring = EventRing(capacity = 8)
        val running = AtomicBoolean(true)
        val fills = AtomicLong(0)
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
                        strategy = "s",
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
        try {
            val ast =
                StrategyAst(
                    name = "s",
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
            val handle =
                StrategyHandle(
                    name = "s",
                    ast = ast,
                    live = live,
                    observability = server,
                    ring = ring,
                    logFile = tmp.resolve("s.log"),
                    startedAt = Instant.now(),
                    fillCount = fills,
                )

            // Signals land in the ring but must not be counted as trades.
            ring.append("signal", JsonObject(emptyMap()))
            ring.append("signal", JsonObject(emptyMap()))
            assertThat(handle.tradeCount).isEqualTo(0)

            // Only fills increment the trade count.
            fills.incrementAndGet()
            fills.incrementAndGet()
            ring.append("signal", JsonObject(emptyMap()))
            assertThat(handle.tradeCount).isEqualTo(2)
        } finally {
            server.close()
        }
    }
}
