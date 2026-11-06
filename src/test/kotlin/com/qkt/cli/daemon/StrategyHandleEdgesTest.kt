package com.qkt.cli.daemon

import com.qkt.app.OrderManager
import java.math.BigDecimal
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A handle's status with pending stack layers, an interrupted stop, and a resync onto a strategy's name. */
class StrategyHandleEdgesTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    @Test
    fun `a session's pending stack layers appear in its status`() {
        val session =
            object : IdleSession() {
                override fun pendingStackLayerInfos() =
                    listOf(
                        OrderManager.PendingStackLayerInfo("stk-1", 2, BigDecimal("2600.5"), "BUY", BigDecimal("0.3")),
                    )
            }

        val snap = sessionStatusSnapshot("book/s1", 3, "s1", 0L, Instant.EPOCH, session)

        assertThat(snap.strategy).isEqualTo("book/s1")
        assertThat(snap.version).isEqualTo(3)
        val layer = snap.pendingStackLayers.single()
        assertThat(layer.stackId).isEqualTo("stk-1")
        assertThat(layer.layer).isEqualTo(2)
        assertThat(layer.triggerPrice).isEqualByComparingTo("2600.5")
        assertThat(layer.side).isEqualTo("BUY")
        assertThat(layer.quantity).isEqualByComparingTo("0.3")
    }

    @Test
    fun `an interrupted stop reports not terminated and keeps the interrupt`() {
        sessions["a"] =
            object : IdleSession() {
                override fun awaitTermination(timeout: Duration): Boolean = throw InterruptedException("stop")
            }
        val handle = registry(stateDir).deploy("a", tmp.resolve("a.qkt"))

        val terminated = handle.awaitStopped(Duration.ofSeconds(1))
        val interrupted = Thread.interrupted()

        assertThat(terminated).isFalse
        assertThat(interrupted).isTrue
        assertThat(sessions.getValue("a").running).isFalse
    }

    @Test
    fun `a portfolio resync onto a standalone strategy's name is refused and closes the replacement`() {
        val registry = registry(stateDir).also { it.deploy("book", tmp.resolve("b.qkt")) }
        val events = mutableListOf<String>()
        val replacement = portfolioRecord(stateDir, events, "book", "a", 2)

        assertThatThrownBy { registry.resyncPortfolio(replacement) }
            .hasMessage("name 'book' already deployed as strategy")
        assertThat(events).contains("stop:book/a:v2")
        assertThat(registry.get("book")).isNotNull
    }
}
