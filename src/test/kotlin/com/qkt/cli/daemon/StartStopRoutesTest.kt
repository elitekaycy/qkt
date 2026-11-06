package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Stopping and restarting portfolios and their children over the control plane, journaled as operator actions. */
class StartStopRoutesTest : DaemonRouteFixture() {
    @TempDir lateinit var tmp: Path

    private val stateDir by lazy { StateDir.resolve(tmp.toString()) }

    private fun journal(): String =
        Files.walk(stateDir.stateRoot.resolve("journal")).use { files ->
            files.filter { Files.isRegularFile(it) }.toList().joinToString("\n") { Files.readString(it) }
        }

    @Test
    fun `stopping a portfolio flattens its children and says when that is not confirmed flat`() {
        val registry = registry(stateDir)
        registry.registerPortfolio(portfolioRecord(stateDir, mutableListOf(), "book", "a", 1))

        val (code, body) = post(plane(registry, stateDir), "/stop/book")

        assertThat(code).isEqualTo(200)
        assertThat(body)
            .contains("\"name\":\"book\"", "\"state\":\"not_flat\"", "\"flat\":false")
            .contains("book/a: this session handle cannot verify venue positions")
        assertThat(registry.getPortfolio("book")).isNull()
        assertThat(registry.get("book/a")).isNull()
        assertThat(journal()).contains("operator_action", "book/a", "default")
    }

    @Test
    fun `stopping a portfolio without flatten leaves the children unflattened`() {
        val registry = registry(stateDir)
        registry.registerPortfolio(portfolioRecord(stateDir, mutableListOf(), "book", "a", 1))

        val (code, body) = post(plane(registry, stateDir), "/stop/book?flatten=false")

        assertThat(code).isEqualTo(200)
        assertThat(body).isEqualTo("""{"name":"book","state":"stopped","trades":0}""")
    }

    @Test
    fun `stopping a child marks it operator stopped and keeps it deployed`() {
        meta["book/a"] = childMeta("book", "a")
        val registry = registry(stateDir).also { it.deploy("book/a", tmp.resolve("a.qkt")) }

        val (code, body) = post(plane(registry, stateDir), "/stop/book/a")

        assertThat(code).isEqualTo(200)
        assertThat(body).isEqualTo("""{"name":"book/a","state":"operator_stopped","trades":0}""")
        assertThat(meta.getValue("book/a").operatorStop.get()).isTrue
        assertThat(meta.getValue("book/a").gateActive.get()).isFalse
        assertThat(registry.get("book/a")).isNotNull
        assertThat(journal()).contains("operator_stopped")
    }

    @Test
    fun `an explicit flatten on a child waits for the verified answer`() {
        meta["book/a"] = childMeta("book", "a", hold = true)
        val registry = registry(stateDir).also { it.deploy("book/a", tmp.resolve("a.qkt")) }

        val (_, body) = post(plane(registry, stateDir), "/stop/book/a?flatten=true")

        assertThat(body).contains("\"state\":\"not_flat\"", "this session handle cannot verify venue positions")
    }

    @Test
    fun `a stop without a name is refused`() {
        assertThat(post(plane(registry(stateDir)), "/stop/").first).isEqualTo(400)
    }

    @Test
    fun `start resumes an operator-stopped child`() {
        meta["book/a"] = childMeta("book", "a").also { it.operatorStop.set(true) }
        val registry = registry(stateDir).also { it.deploy("book/a", tmp.resolve("a.qkt")) }

        val (code, body) = post(plane(registry, stateDir), "/start/book/a")

        assertThat(code).isEqualTo(200)
        assertThat(body).isEqualTo("""{"name":"book/a","state":"resumed"}""")
        assertThat(meta.getValue("book/a").operatorStop.get()).isFalse
        assertThat(journal()).contains("resumed")
    }

    @Test
    fun `start refuses standalone strategies portfolios blank and unknown names`() {
        val registry = registry(stateDir).also { it.deploy("solo", tmp.resolve("s.qkt")) }
        registry.registerPortfolio(portfolioRecord(stateDir, mutableListOf(), "book", "a", 1))
        val p = plane(registry)

        assertThat(post(p, "/start/solo")).isEqualTo(400 to """{"error":"strategy 'solo' has no paused state"}""")
        assertThat(post(p, "/start/book")).isEqualTo(
            400 to """{"error":"portfolio 'book' cannot be started; use deploy"}""",
        )
        assertThat(post(p, "/start/").first).isEqualTo(400)
        assertThat(post(p, "/start/nope")).isEqualTo(404 to """{"error":"unknown name: nope"}""")
    }

    @Test
    fun `kill and resume refuse a bad flatten and unknown names`() {
        val p = plane(registry(stateDir))

        assertThat(post(p, "/kill?flatten=maybe")).isEqualTo(400 to """{"error":"invalid 'flatten' query param"}""")
        assertThat(post(p, "/kill/ghost")).isEqualTo(404 to """{"error":"unknown name: ghost"}""")
        assertThat(post(p, "/resume/ghost")).isEqualTo(404 to """{"error":"unknown name: ghost"}""")
    }

    @Test
    fun `kill with flatten false halts without flattening`() {
        val registry = registry(stateDir).also { it.deploy("solo", tmp.resolve("s.qkt")) }

        val (code, body) = post(plane(registry), "/kill/solo?flatten=false")

        assertThat(code).isEqualTo(200)
        assertThat(body).contains("\"state\":\"killed\"", "\"flatten\":false", "\"affected\":[\"solo\"]")
    }

    @Test
    fun `kill with flatten reports the unverified flatten and journals it`() {
        val registry = registry(stateDir).also { it.deploy("solo", tmp.resolve("s.qkt")) }

        val (code, body) = post(plane(registry, stateDir), "/kill/solo?flatten=true")

        assertThat(code).isEqualTo(200)
        assertThat(body)
            .contains("\"flatten\":true", "\"flattenVerified\":false", "\"remainingTickets\":[]")
            .contains("\"flattenDetails\":{\"solo\":\"this session handle cannot verify venue positions\"}")
        assertThat(journal()).contains("kill", "flattenVerified")
    }
}
