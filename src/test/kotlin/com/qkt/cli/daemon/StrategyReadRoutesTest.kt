package com.qkt.cli.daemon

import com.qkt.app.PositionDelta
import com.qkt.app.PositionProtectionDelta
import com.qkt.app.ReconcileReport
import com.qkt.common.Side
import com.qkt.notify.AtomicNotifierMetrics
import java.math.BigDecimal
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** The read routes over deployed strategies: reconcile, metrics, health, status and latency. */
class StrategyReadRoutesTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    @Test
    fun `reconcile renders position and protection deltas with both equities`() {
        sessions["a"] =
            object : IdleSession() {
                override fun reconcile() =
                    ReconcileReport(
                        deltas = listOf(PositionDelta("XAUUSD", BigDecimal("1"), BigDecimal("0.5"), Side.BUY)),
                        engineEquity = BigDecimal("1000"),
                        brokerEquity = BigDecimal("999.5"),
                        protectionDeltas =
                            listOf(
                                PositionProtectionDelta(
                                    "7",
                                    "XAUUSD",
                                    BigDecimal("2600"),
                                    null,
                                    null,
                                    BigDecimal("2700"),
                                ),
                            ),
                        brokerReadError = "partial",
                    )
            }
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }

        val (code, body) = get(plane(registry), "/reconcile/a")

        assertThat(code).isEqualTo(200)
        assertThat(body)
            .contains(
                "\"strategy\":\"a\"",
                "\"clean\":false",
                "\"engineQty\":\"1\"",
                "\"brokerQty\":\"0.5\"",
                "\"side\":\"BUY\"",
            ).contains("\"ticket\":\"7\"", "\"requestedStopLoss\":\"2600\"", "\"brokerStopLoss\":null")
            .contains("\"brokerTakeProfit\":\"2700\"", "\"brokerReadError\":\"partial\"", "\"brokerEquity\":\"999.5\"")
    }

    @Test
    fun `reconcile says unsupported when the venue cannot and 404s an unknown name`() {
        sessions["b"] =
            object : IdleSession() {
                override fun reconcile() =
                    ReconcileReport(
                        deltas = listOf(PositionDelta("X", BigDecimal.ONE, BigDecimal.ONE)),
                        engineEquity = BigDecimal.TEN,
                        brokerEquity = null,
                    )
            }
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }
        registry.deploy("b", tmp.resolve("b.qkt"))
        val p = plane(registry)

        assertThat(get(p, "/reconcile/a")).isEqualTo(200 to """{"strategy":"a","supported":false}""")
        assertThat(get(p, "/reconcile/b").second).contains("\"side\":null", "\"brokerEquity\":null")
        assertThat(get(p, "/reconcile/zz").first).isEqualTo(404)
    }

    @Test
    fun `an exception inside a route answers 500 with its message`() {
        sessions["a"] =
            object : IdleSession() {
                override fun reconcile(): ReconcileReport = error("venue said \"no\"")
            }
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }

        val (code, body) = get(plane(registry), "/reconcile/a")

        assertThat(code).isEqualTo(500)
        assertThat(body).isEqualTo("""{"error":"venue said 'no'"}""")
    }

    @Test
    fun `metrics expose per-strategy trades and the notifier counters`() {
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }

        val (code, body) = get(plane(registry, notifierMetrics = AtomicNotifierMetrics()), "/metrics")

        assertThat(code).isEqualTo(200)
        assertThat(body)
            .contains("qkt_strategies_running 1", "qkt_strategy_trades_total{strategy=\"a\"} 0")
            .contains("qkt_notifier_sent_total 0", "qkt_notifier_dropped_total 0", "qkt_notifier_failed_total 0")
            .contains("qkt_notifier_rate_limit_hits_total 0", "qkt_notifier_degraded_mode 0")
    }

    @Test
    fun `health lists each strategy with its halt and queue state`() {
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }

        val (_, body) = get(plane(registry), "/health")

        assertThat(body)
            .contains("\"status\":\"ok\"", "\"strategies\":1", "\"name\":\"a\"", "\"running\":true", "\"halted\":false")
            .contains("\"haltReason\":null", "\"lastEventAgeMs\":null", "\"droppedTicks\":0")
    }

    @Test
    fun `status of a child carries its portfolio gate fields`() {
        meta["book/x"] = childMeta("book", "x", hold = true)
        val registry = registry(stateDir).also { it.deploy("book/x", tmp.resolve("x.qkt")) }

        val (code, body) = get(plane(registry), "/status/book/x")

        assertThat(code).isEqualTo(200)
        assertThat(body)
            .contains("\"kind\":\"child\"", "\"parent\":\"book\"", "\"alias\":\"x\"", "\"gateActive\":true")
            .contains("\"operatorStop\":false", "\"hold\":true")
    }

    @Test
    fun `status refuses a blank name and reports an unreachable strategy`() {
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }
        val p = plane(registry)
        registry.get("a")!!.observability.close()

        assertThat(get(p, "/status/").first).isEqualTo(400)
        assertThat(get(p, "/status/a")).isEqualTo(502 to """{"error":"strategy /status unreachable"}""")
        assertThat(get(p, "/status").second).isEqualTo("[]")
    }

    @Test
    fun `latency aggregates each reachable strategy under its name`() {
        val registry = registry(stateDir).also { it.deploy("a", tmp.resolve("a.qkt")) }
        registry.deploy("b", tmp.resolve("b.qkt"))
        val p = plane(registry)
        registry.get("b")!!.observability.close()

        val (code, body) = get(p, "/latency")

        assertThat(code).isEqualTo(200)
        assertThat(body).startsWith("{\"a\":").doesNotContain("\"b\":")
    }

    @Test
    fun `list shows a child whose gate is closed as idle`() {
        meta["book/x"] = childMeta("book", "x").also { it.gateActive.set(false) }
        val registry = registry(stateDir).also { it.deploy("book/x", tmp.resolve("x.qkt")) }

        val (code, body) = get(plane(registry, stateDir), "/list")

        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"book/x\"", "\"idle\"")
    }
}
