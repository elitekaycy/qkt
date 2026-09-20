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
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StrategyHandleTradeCountTest {
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
