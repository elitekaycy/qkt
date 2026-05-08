package com.qkt.cli.daemon

import okhttp3.OkHttpClient
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ControlPlaneTest {
    private val noopFactory =
        StrategyHandle.Factory { _, _ ->
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
}
