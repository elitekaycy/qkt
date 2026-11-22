package com.qkt.cli.daemon

import com.qkt.cli.UserDirs
import com.qkt.cli.daemon.routes.handleHealth
import com.qkt.cli.observe.FakeHttpExchange
import com.qkt.strategy.Signal
import java.io.ByteArrayOutputStream
import java.nio.file.Path
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class SmallDaemonUnitsTest : ListRouteFixture() {
    @TempDir
    lateinit var tmp: Path

    @Test
    fun `the state directory is the argument, else QKT_STATE_DIR, else the user's state home`() {
        val userDirs = UserDirs(osName = "linux", env = emptyMap(), home = tmp)

        assertThat(
            StateDir.resolve("$tmp/arg", mapOf("QKT_STATE_DIR" to "$tmp/env"), userDirs).root,
        ).isEqualTo(tmp.resolve("arg"))
        assertThat(
            StateDir.resolve(null, mapOf("QKT_STATE_DIR" to "$tmp/env"), userDirs).root,
        ).isEqualTo(tmp.resolve("env"))
        assertThat(StateDir.resolve(null, emptyMap(), userDirs).root).isEqualTo(userDirs.stateHome())
        assertThat(StateDir.resolve(env = mapOf("QKT_STATE_DIR" to "$tmp/env"), userDirs = userDirs).root)
            .isEqualTo(tmp.resolve("env"))
    }

    @Test
    fun `an armed latch is reported by name, blank when unnamed`() {
        val latch =
            Signal.ArmLatch(
                com.qkt.app.LatchManagerFixture
                    .compiledLatch("2000", "5", 1_000L),
                com.qkt.app.LatchManagerFixture
                    .ec("XAUUSD"),
            )

        assertThat(signalToJson(latch).toString()).isEqualTo("""{"kind":"arm_latch","name":""}""")
    }

    @Test
    fun `a sample can be built from a double`() {
        assertThat(PrometheusFormat.Sample(mapOf("strategy" to "a"), 1.5).value).isEqualTo("1.5")
    }

    @Test
    fun `health without pending deploys or unverified accounts is ok`() {
        val registry = StrategyRegistry(stubFactory(StateDir.resolve(tmp.toString())))
        registry.deploy("alpha", tmp.resolve("alpha.qkt"))
        val body = ByteArrayOutputStream()
        val exchange = FakeHttpExchange("GET", body)

        handleHealth(exchange, registry, Instant.now())

        assertThat(exchange.responseCode).isEqualTo(200)
        assertThat(body.toString()).contains("\"status\":\"ok\"").contains("\"name\":\"alpha\"")
    }
}
