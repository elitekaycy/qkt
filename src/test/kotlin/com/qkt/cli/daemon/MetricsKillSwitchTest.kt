package com.qkt.cli.daemon

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * `QKT_METRICS_PROMETHEUS=false` removes the built-in `/metrics` route. Operators running
 * their own exporter sidecar (per docs/operations/metrics.md) typically turn this off to
 * stop the daemon from carrying an unused endpoint.
 */
class MetricsKillSwitchTest {
    private val httpClient: HttpClient = HttpClient.newHttpClient()

    private val noopFactory =
        StrategyHandle.Factory { _, _, _ ->
            error("noop factory should not be invoked in this test")
        }

    private fun get(
        port: Int,
        path: String,
    ): HttpResponse<String> =
        httpClient.send(
            HttpRequest
                .newBuilder()
                .uri(URI("http://127.0.0.1:$port$path"))
                .GET()
                .build(),
            HttpResponse.BodyHandlers.ofString(),
        )

    @Test
    fun `enabled metrics endpoint returns 200 with Prometheus text format`() {
        val plane =
            ControlPlane(
                registry = StrategyRegistry(noopFactory),
                startedAt = Instant.now(),
                prometheusMetricsEnabled = true,
            )
        plane.start()
        try {
            val resp = get(plane.boundPort, "/metrics")
            assertThat(resp.statusCode()).isEqualTo(200)
            assertThat(resp.body()).contains("qkt_daemon_uptime_seconds")
            assertThat(resp.headers().firstValue("Content-Type").orElse(""))
                .contains("text/plain")
        } finally {
            plane.close()
        }
    }

    @Test
    fun `disabled metrics endpoint returns 404`() {
        val plane =
            ControlPlane(
                registry = StrategyRegistry(noopFactory),
                startedAt = Instant.now(),
                prometheusMetricsEnabled = false,
            )
        plane.start()
        try {
            val resp = get(plane.boundPort, "/metrics")
            assertThat(resp.statusCode()).isEqualTo(404)
            // Health endpoint is still reachable — only /metrics is suppressed.
            val health = get(plane.boundPort, "/health")
            assertThat(health.statusCode()).isEqualTo(200)
        } finally {
            plane.close()
        }
    }
}
