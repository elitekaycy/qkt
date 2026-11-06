package com.qkt.cli.daemon

import com.qkt.app.ReconcileException
import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** `POST /deploy` and `POST /resync` of a standalone strategy: bad requests and each way the session fails. */
class DeployResyncRoutesTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    private val strategy by lazy {
        tmp.resolve("s.qkt").also {
            Files.writeString(
                it,
                """
                STRATEGY s VERSION 1
                SYMBOLS
                    x = BACKTEST:X EVERY 1m
                RULES
                    WHEN x.close > 0 THEN BUY x SIZING 1
                """.trimIndent(),
            )
        }
    }

    private fun body(
        name: String,
        file: Path = strategy,
        dryRun: Boolean = false,
    ) = """{"file":"$file","name":"$name","dryRun":$dryRun}"""

    @Test
    fun `deploy and resync refuse bodies that are not a deployable request`() {
        val p = plane(registry(stateDir), stateDir)
        val broken = tmp.resolve("broken.qkt").also { Files.writeString(it, "STRATEGY nope") }

        for (route in listOf("/deploy", "/resync")) {
            assertThat(post(p, route, "not json")).isEqualTo(400 to """{"error":"invalid JSON body"}""")
            assertThat(post(p, route, "[1]")).isEqualTo(400 to """{"error":"body must be a JSON object"}""")
            assertThat(post(p, route, """{"name":"a"}""").first).isEqualTo(400)
            assertThat(post(p, route, body("a/b")).second).contains("top-level name must not contain '/': a/b")
            assertThat(post(p, route, body("a", tmp.resolve("none.qkt"))).second).contains("file not found")
            assertThat(post(p, route, body("a", broken)).let { it.first to it.second.contains("parse failed") })
                .isEqualTo(400 to true)
        }
    }

    @Test
    fun `a deploy whose session fails answers by the kind of failure`() {
        val registry = registry(stateDir)
        val p = plane(registry, stateDir)
        failures["r"] = ReconcileException("venue holds \"2\" lots")
        failures["c"] = IllegalStateException("account busy")
        failures["x"] = RuntimeException("socket closed")

        assertThat(post(p, "/deploy", body("r")))
            .isEqualTo(409 to """{"error":"venue holds '2' lots","kind":"reconcile-mismatch"}""")
        assertThat(post(p, "/deploy", body("c"))).isEqualTo(409 to """{"error":"account busy"}""")
        assertThat(post(p, "/deploy", body("x"))).isEqualTo(500 to """{"error":"socket closed"}""")
        assertThat(post(p, "/deploy", body("bad name")).first).isEqualTo(400)
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `a deployed strategy is journaled with its promotion state`() {
        val registry = registry(stateDir)

        val (code, reply) = post(plane(registry, stateDir), "/deploy?reconcile=ignore-mismatches", body("s"))

        assertThat(code).isEqualTo(200)
        assertThat(reply).contains("\"name\":\"s\"", "\"kind\":\"strategy\"", "\"state\":\"running\"")
        assertThat(registry.get("s")).isNotNull
    }

    @Test
    fun `resync plans a dry run and replaces a running strategy`() {
        val registry = registry(stateDir).also { it.deploy("s", strategy) }
        val p = plane(registry, stateDir)

        val (planCode, plan) = post(p, "/resync", body("s", dryRun = true))
        val (code, reply) = post(p, "/resync", body("s"))

        assertThat(planCode).isEqualTo(200)
        assertThat(plan).contains("\"state\":\"planned\"", "\"dryRun\":true", "\"affected\":[\"s\"]")
        assertThat(code).isEqualTo(200)
        assertThat(reply).contains("\"state\":\"running\"", "\"dryRun\":false", "\"affected\":[\"s\"]")
        val journal =
            Files.walk(stateDir.stateRoot.resolve("journal")).use { f ->
                f.filter { Files.isRegularFile(it) }.toList().joinToString { Files.readString(it) }
            }
        assertThat(journal).contains("resync", "s.qkt")
    }

    @Test
    fun `resync refuses unknown strategies and portfolio children`() {
        meta["kid"] = childMeta("book", "kid")
        val registry = registry(stateDir).also { it.deploy("kid", strategy) }
        val p = plane(registry, stateDir)

        assertThat(post(p, "/resync", body("ghost"))).isEqualTo(404 to """{"error":"unknown strategy: ghost"}""")
        assertThat(post(p, "/resync", body("kid")))
            .isEqualTo(409 to """{"error":"strategy 'kid' is a portfolio child; resync the parent"}""")
    }

    @Test
    fun `a resync whose replacement fails answers by the kind of failure`() {
        val registry = registry(stateDir)
        listOf("r", "c", "i", "x").forEach { registry.deploy(it, strategy) }
        val p = plane(registry, stateDir)
        failures["r"] = ReconcileException("mismatch")
        failures["c"] = IllegalStateException("busy")
        failures["i"] = IllegalArgumentException("bad")
        failures["x"] = RuntimeException("boom")

        assertThat(post(p, "/resync", body("r"))).isEqualTo(
            409 to """{"error":"mismatch","kind":"reconcile-mismatch"}""",
        )
        assertThat(post(p, "/resync", body("c"))).isEqualTo(409 to """{"error":"busy"}""")
        assertThat(post(p, "/resync", body("i"))).isEqualTo(400 to """{"error":"bad"}""")
        assertThat(post(p, "/resync", body("x"))).isEqualTo(500 to """{"error":"boom"}""")
    }
}
