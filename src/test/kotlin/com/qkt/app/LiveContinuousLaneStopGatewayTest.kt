package com.qkt.app

import com.qkt.cli.daemon.RegistryDaemonControl
import com.qkt.cli.daemon.StrategyHandle
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.cli.daemon.Target
import com.qkt.cli.observe.EventRing
import com.qkt.cli.observe.ObservabilityServer
import com.qkt.cli.observe.StatusSnapshot
import com.qkt.dsl.ast.StrategyAst
import com.qkt.persistence.FileStatePersistor
import com.qkt.persistence.PersistedStreamLane
import com.qkt.persistence.PersistedStreamStrategy
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The soak's `soak_o14_roll`, end to end: a session starts over a lane left flat but stopped by a failed roll.
 * Starting and the risk layer's own resume leave the stop standing; the operator's resume clears it for good, and
 * the strategy's next order reaches the gateway.
 */
class LiveContinuousLaneStopGatewayTest {
    private val stream = "BINANCE_UM:BTCUSDT@front"

    private fun stopped(state: Path) =
        FileStatePersistor(state)
            .loadStreamLane("hold", stream)
            ?.strategies
            ?.single()
            ?.stopped

    private fun control(
        live: LiveSessionHandle,
        dir: Path,
    ): RegistryDaemonControl {
        val registry =
            StrategyRegistry(
                StrategyHandle.Factory { name, _, _ ->
                    StrategyHandle(
                        name = name,
                        ast =
                            StrategyAst(
                                name = name,
                                version = 1,
                                streams = emptyList(),
                                constants = emptyList(),
                                lets = emptyList(),
                                defaults = null,
                                rules = emptyList(),
                            ),
                        live = live,
                        observability = observability(name),
                        ring = EventRing(capacity = 8),
                        logFile = dir.resolve("$name.log"),
                        startedAt = Instant.EPOCH,
                    )
                },
            )
        registry.deploy("hold", dir.resolve("hold.qkt"))
        return RegistryDaemonControl(registry)
    }

    private fun observability(name: String) =
        ObservabilityServer(
            ring = EventRing(capacity = 8),
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
                    positions = emptyList(),
                    lastTrade = null,
                )
            },
            running = { true },
            onStop = {},
            bind = "127.0.0.1",
            port = 0,
        )

    @Test
    fun `operator resume clears a flat lane's stop for good, and the next order reaches the venue`(
        @TempDir dir: Path,
    ) {
        LiveContinuousGatewayFixture(dir.resolve("rolls")).use { f ->
            val state = dir.resolve("state")
            FileStatePersistor(state).saveStreamLane(
                "hold",
                PersistedStreamLane(
                    stream,
                    "BINANCE_UM:${f.codes[1]}",
                    listOf(PersistedStreamStrategy("hold", BigDecimal.ZERO, "$stream stopped: roll failed")),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    emptyList(),
                    null,
                ),
            )
            val session = f.start(FileStatePersistor(state))
            try {
                val stop = session.handle.streamStops().single()
                assertThat(stop.stream).isEqualTo(stream)
                assertThat(stop.reason).contains("roll failed")

                session.handle.resume()
                assertThat(stopped(state)).contains("roll failed")

                val result = control(session.handle, dir).resume(Target.Strategy("hold"))
                assertThat(result.streamStops.single().cleared).isTrue()
                assertThat(stopped(state)).isNull()
                assertThat(session.handle.streamStops()).isEmpty()

                f.quote()
                f.await(150) { f.fake.submits.any { it.symbol == f.codes[1] } }
            } finally {
                session.stop()
            }
            assertThat(stopped(state)).isNull()
        }
    }
}
