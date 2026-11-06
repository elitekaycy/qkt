package com.qkt.cli.daemon

import java.nio.file.Path
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** Each [ControlClient] call reaches the daemon route it names, with its options as query parameters. */
class ControlClientCallsTest {
    private val server = MockWebServer().apply { start() }

    @TempDir lateinit var dir: Path

    @AfterEach
    fun stop() = server.shutdown()

    private fun client() = ControlClient(StateDir.resolve(dir.toString()), explicitPort = server.port)

    private fun answer(body: String = "{}") = server.enqueue(MockResponse().setBody(body))

    private fun request(): String = server.takeRequest().let { "${it.method} ${it.path}" }

    @Test
    fun `reads hit their routes and return the body`() {
        repeat(4) { answer("""{"n":$it}""") }
        val c = client()

        assertThat(c.status()).isEqualTo("""{"n":0}""")
        assertThat(c.status("trend")).isEqualTo("""{"n":1}""")
        assertThat(c.latency()).isEqualTo("""{"n":2}""")
        assertThat(c.reconcile("trend")).isEqualTo("""{"n":3}""")
        assertThat((1..4).map { request() })
            .containsExactly("GET /status", "GET /status/trend", "GET /latency", "GET /reconcile/trend")
    }

    @Test
    fun `logs carry lines since and follow only when given`() {
        repeat(2) { answer("line") }
        val c = client()

        c.logs("trend").use { assertThat(it.body!!.string()).isEqualTo("line") }
        c.logs("trend", lines = 50, since = "10m", follow = true).close()

        assertThat(request()).isEqualTo("GET /logs/trend")
        assertThat(request()).isEqualTo("GET /logs/trend?lines=50&since=10m&follow=true")
    }

    @Test
    fun `mutations post to their routes for one strategy or all`() {
        repeat(8) { answer() }
        val c = client()

        c.start("trend")
        c.halt()
        c.halt("trend")
        c.kill()
        c.kill("trend", flatten = true)
        c.resume()
        c.resume("trend")
        c.stop("trend", flatten = true, timeoutMs = 500)

        assertThat((1..8).map { request() })
            .containsExactly(
                "POST /start/trend",
                "POST /halt",
                "POST /halt/trend",
                "POST /kill",
                "POST /kill/trend?flatten=true",
                "POST /resume",
                "POST /resume/trend",
                "POST /stop/trend?flatten=true&timeout=500",
            )
    }

    @Test
    fun `deploy and resync send the file and encode the waiver`() {
        repeat(2) { answer() }
        val c = client()

        c.deploy("trend", dir.resolve("t.qkt"), ignoreMismatches = true, waiver = "soak gate", waiverReason = "a&b")
        c.resync("trend", dir.resolve("t.qkt"), dryRun = true)

        val deploy = server.takeRequest()
        assertThat(deploy.path).isEqualTo("/deploy?reconcile=ignore-mismatches&waive=soak+gate&reason=a%26b")
        assertThat(deploy.body.readUtf8()).contains("\"name\":\"trend\"", "t.qkt")
        val resync = server.takeRequest()
        assertThat(resync.path).isEqualTo("/resync")
        assertThat(resync.body.readUtf8()).contains("\"dryRun\":true")
    }

    @Test
    fun `a non-2xx reply is a daemon error with its code and body`() {
        server.enqueue(MockResponse().setResponseCode(409).setBody("busy"))

        assertThatThrownBy { client().halt("trend") }
            .isInstanceOfSatisfying(ControlClient.DaemonError::class.java) {
                assertThat(it.code).isEqualTo(409)
                assertThat(it.body).isEqualTo("busy")
            }
    }

    @Test
    fun `no control port means no daemon running`() {
        assertThatThrownBy { ControlClient(StateDir.resolve(dir.toString())).health() }
            .isInstanceOf(ControlClient.NoDaemonRunningException::class.java)
            .hasMessageContaining("control.port")
    }
}
