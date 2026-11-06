package com.qkt.cli.observe

import com.qkt.observability.LatencyRegistry
import com.qkt.observability.LatencyStage
import com.qkt.observability.LatencyTracker
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerLatencyTest : ObservabilityServerFixture() {
    private fun latencyServer(provider: () -> LatencyRegistry.Report) =
        ObservabilityServer(
            ring = EventRing(),
            statusProvider = { error("unused") },
            running = { true },
            onStop = {},
            bind = "127.0.0.1",
            port = 0,
            latencyProvider = provider,
        ).also { it.start() }

    private fun ObservabilityServer.get(path: String): Response =
        client.newCall(Request.Builder().url("http://127.0.0.1:$boundPort$path").build()).execute()

    private fun ObservabilityServer.post(path: String): Response =
        client
            .newCall(
                Request
                    .Builder()
                    .url("http://127.0.0.1:$boundPort$path")
                    .post(ByteArray(0).toRequestBody(null))
                    .build(),
            ).execute()

    @Test
    fun `latency renders every strategy's stages with their percentiles`() {
        val snapshot = LatencyTracker.Snapshot(count = 3, p50Nanos = 10, p95Nanos = 20, p99Nanos = 30, maxNanos = 40)
        latencyServer {
            LatencyRegistry.Report(
                enabled = true,
                strategies =
                    mapOf(
                        "alpha" to mapOf(LatencyStage.TICK_PROCESSING to snapshot),
                    ),
            )
        }.use { server ->
            val (code, body) = server.get("/latency").use { it.code to it.body!!.string() }

            assertThat(code).isEqualTo(200)
            assertThat(body).isEqualTo(
                """{"enabled":true,"strategies":{"alpha":{"TICK_PROCESSING":{"count":3,"p50Nanos":10,""" +
                    """"p95Nanos":20,"p99Nanos":30,"maxNanos":40}}}}""",
            )
        }
    }

    @Test
    fun `a failing latency provider answers 500 with its message`() {
        latencyServer { error("registry \"gone\"") }.use { server ->
            val (code, body) = server.get("/latency").use { it.code to it.body!!.string() }

            assertThat(code).isEqualTo(500)
            assertThat(body).isEqualTo("""{"error":"registry 'gone'"}""")
        }
    }

    @Test
    fun `latency logs and events refuse anything but GET`() {
        latencyServer { LatencyRegistry.Report(enabled = false, strategies = emptyMap()) }.use { server ->
            listOf("/latency", "/logs", "/events").forEach { path ->
                val (code, body) = server.post(path).use { it.code to it.body!!.string() }
                assertThat(code).withFailMessage(path).isEqualTo(405)
                assertThat(body).isEqualTo("""{"error":"method not allowed"}""")
            }
        }
    }

    @Test
    fun `logs rejects a limit below one`() {
        latencyServer { LatencyRegistry.Report(enabled = false, strategies = emptyMap()) }.use { server ->
            val (code, body) = server.get("/logs?limit=0").use { it.code to it.body!!.string() }

            assertThat(code).isEqualTo(400)
            assertThat(body).isEqualTo("""{"error":"invalid 'limit' query param"}""")
        }
    }
}
