package com.qkt.cli.daemon

import com.qkt.cli.Args
import com.qkt.cli.DaemonCommand
import com.qkt.common.FixedClock
import com.qkt.marketdata.Tick
import com.qkt.marketdata.TickFeed
import com.qkt.marketdata.live.LiveTickFeed
import com.qkt.marketdata.live.LiveTickSource
import com.qkt.marketdata.source.MarketSource
import com.qkt.marketdata.source.MarketSourceCapability
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * A gateway outage longer than the live feed's reconnect budget ends the session (it must not trade
 * on stale prices); the daemon then redeploys it through the boot deploy path once the venue is back,
 * without a restart.
 */
class FeedLossRedeployerTest {
    @TempDir
    lateinit var tmp: Path

    private val clock = FixedClock(1_000L)
    private val creates = AtomicInteger(0)

    /** True while the "gateway" is down: a session created then loses its feed at once. */
    private val gatewayDown = AtomicBoolean(true)

    /** True while a deploy cannot even build its session (the gateway refuses the warmup reads). */
    @Volatile
    private var gatewayDownAtCreate = false
    private lateinit var registry: StrategyRegistry

    @AfterEach
    fun cleanup() {
        if (::registry.isInitialized) registry.stopAll()
    }

    @Test
    fun `a session ended by an exhausted reconnect budget is redeployed without a daemon restart`() {
        val (retrier, redeployer) = wire()
        val file = strategyFile("alpha")
        registry.deploy("alpha", file)
        val lost = registry.get("alpha")!!
        assertThat(lost.live.awaitTermination(Duration.ofSeconds(5))).isTrue()
        assertThat(lost.live.unexpectedFeedEnd()).contains("reconnect budget")

        // The defect: nothing else brings it back. The boot retrier has nothing pending and the
        // registry still holds the dead deployment, so it stays stopped until an operator acts.
        assertThat(retrier.retryDue(clock.now() + 3_600_000L)).isEmpty()
        assertThat(registry.get("alpha")).isSameAs(lost)
        assertThat(lost.isRunning()).isFalse()

        assertThat(redeployer.sweep()).containsExactly("alpha")
        assertThat(registry.get("alpha")).isNull()
        assertThat(retrier.pending().single().lastError).contains("live feed lost").contains("reconnect budget")

        // Still down at the first retry: the deploy fails and stays pending on the backoff.
        clock.advanceTo(clock.now() + 60_000L)
        gatewayDownAtCreate = true
        assertThat(retrier.retryDue(clock.now())).isEmpty()
        assertThat(retrier.pending().single().attempts).isEqualTo(2)

        // Gateway back: the next retry deploys a fresh session from the same file.
        gatewayDown.set(false)
        gatewayDownAtCreate = false
        clock.advanceTo(retrier.pending().single().nextAttemptAtMs)
        assertThat(retrier.retryDue(clock.now())).containsExactly("alpha")
        val back = registry.get("alpha")!!
        assertThat(back).isNotSameAs(lost)
        assertThat(back.isRunning()).isTrue()
        assertThat(back.sourceFile).isEqualTo(file.toAbsolutePath().normalize())
        assertThat(back.live.unexpectedFeedEnd()).isNull()
        assertThat(redeployer.sweep()).isEmpty()
    }

    @Test
    fun `a session stopped on request is never redeployed`() {
        gatewayDown.set(false)
        val (retrier, redeployer) = wire()
        registry.deploy("beta", strategyFile("beta"))
        val handle = registry.get("beta")!!

        handle.live.stop()
        assertThat(handle.live.awaitTermination(Duration.ofSeconds(5))).isTrue()

        assertThat(handle.live.unexpectedFeedEnd()).isNull()
        assertThat(redeployer.sweep()).isEmpty()
        assertThat(registry.get("beta")).isSameAs(handle)
        assertThat(retrier.pending()).isEmpty()
    }

    private fun wire(): Pair<AutoDeployRetrier, FeedLossRedeployer> {
        val factory =
            StrategyHandle.RealFactory(
                stateDir = StateDir.resolve(tmp.resolve("state").toString()),
                marketSourceProvider = {
                    creates.incrementAndGet()
                    check(!gatewayDownAtCreate) { "MT5 gateway unreachable" }
                    OutageSource(gatewayDown.get())
                },
            )
        registry = StrategyRegistry(factory)
        val retrier =
            AutoDeployRetrier(
                deploy = {
                    name,
                    file,
                    ->
                    DaemonCommand(Args(arrayOf("daemon"))).deployLoadDirFile(name, file, registry, null)
                },
                alreadyDeployed = { registry.get(it) != null },
                clock = clock,
                log = {},
            )
        return retrier to FeedLossRedeployer(registry, retrier, log = {})
    }

    private fun strategyFile(name: String): Path {
        val dir = Files.createDirectories(tmp.resolve("strategies"))
        return dir.resolve("$name.qkt").also {
            Files.writeString(
                it,
                """
STRATEGY ${name}_strategy VERSION 1

SYMBOLS
    btc = BACKTEST:BTCUSDT EVERY 1m

RULES
    WHEN btc.close > 100
    THEN BUY btc SIZING 1
                """.trimIndent(),
            )
        }
    }

    /** A live source that is either disconnected for good (gateway down) or connected and quiet. */
    private class OutageSource(
        private val down: Boolean,
    ) : MarketSource {
        override val name: String = "outage"
        override val capabilities: Set<MarketSourceCapability> = setOf(MarketSourceCapability.LIVE_TICKS)

        override fun supports(symbol: String): Boolean = true

        override fun liveTicks(symbols: List<String>): TickFeed =
            if (down) {
                LiveTickFeed(DisconnectedSource(), pollIntervalMs = 5L, reconnectBudgetMs = 50L)
            } else {
                QuietFeed()
            }
    }

    private class DisconnectedSource : LiveTickSource {
        override fun start(
            onTick: (Tick) -> Unit,
            onError: (Throwable) -> Unit,
            onDisconnect: () -> Unit,
            onReconnect: () -> Unit,
        ) = onDisconnect()

        override fun stop() = Unit
    }

    private class QuietFeed : TickFeed {
        private val closed = CountDownLatch(1)

        override fun next(): Tick? {
            closed.await(30, TimeUnit.SECONDS)
            return null
        }

        override fun close() = closed.countDown()
    }
}
