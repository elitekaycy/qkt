package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ControlPlaneTest {
    private val noopFactory =
        StrategyHandle.Factory { _, _, _ ->
            error("noop factory should not be invoked in this test")
        }

    @Test
    fun `health returns 200 with strategy count and uptimeMs`() {
        val registry = StrategyRegistry(noopFactory)
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        try {
            val client = OkHttpClient()
            val resp =
                client
                    .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/health").build())
                    .execute()
            assertThat(resp.code).isEqualTo(200)
            val body = resp.body!!.string()
            assertThat(body).contains("\"status\":\"ok\"")
            assertThat(body).contains("\"strategies\":0")
            assertThat(body).contains("\"uptimeMs\":")
        } finally {
            plane.close()
        }
    }

    @Test
    fun `unknown route returns 404`() {
        val registry = StrategyRegistry(noopFactory)
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        try {
            val client = OkHttpClient()
            val resp =
                client
                    .newCall(Request.Builder().url("http://127.0.0.1:${plane.boundPort}/nonexistent").build())
                    .execute()
            assertThat(resp.code).isEqualTo(404)
        } finally {
            plane.close()
        }
    }

    @Test
    fun `binds to 127_0_0_1 only`() {
        val registry = StrategyRegistry(noopFactory)
        val plane = ControlPlane(registry, port = 0)
        plane.start()
        try {
            assertThat(plane.boundHost).isEqualTo("127.0.0.1")
            assertThat(plane.boundPort).isGreaterThan(0)
        } finally {
            plane.close()
        }
    }

    @Test
    fun `mutations require bearer auth while health remains public`(
        @TempDir tmp: Path,
    ) {
        val registry = StrategyRegistry(noopFactory)
        val shutdowns = AtomicInteger()
        val plane =
            ControlPlane(
                registry,
                port = 0,
                stateDir = StateDir.resolve(tmp.toString()),
                controlToken = "operator-secret",
                shutdownHook = { shutdowns.incrementAndGet() },
            )
        plane.start()
        try {
            val http = OkHttpClient()
            val base = "http://127.0.0.1:${plane.boundPort}"
            http
                .newCall(
                    Request
                        .Builder()
                        .url("$base/health")
                        .build(),
                ).execute()
                .use { assertThat(it.code).isEqualTo(200) }
            http
                .newCall(
                    Request
                        .Builder()
                        .url("$base/shutdown")
                        .post("".toRequestBody())
                        .build(),
                ).execute()
                .use { assertThat(it.code).isEqualTo(401) }
            http
                .newCall(
                    Request
                        .Builder()
                        .url("$base/shutdown")
                        .header("Authorization", "Bearer wrong-secret")
                        .post("".toRequestBody())
                        .build(),
                ).execute()
                .use { assertThat(it.code).isEqualTo(401) }
            assertThat(shutdowns.get()).isZero()

            val stateDir = StateDir.resolve(tmp.toString())
            Files.writeString(stateDir.controlTokenFile, "operator-secret")
            val response = ControlClient(stateDir, explicitPort = plane.boundPort).shutdown()

            assertThat(response).contains("\"status\":\"accepted\"")
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (shutdowns.get() == 0 && System.nanoTime() < deadline) Thread.sleep(10)
            assertThat(shutdowns.get()).isEqualTo(1)
        } finally {
            plane.close()
        }
    }
}
