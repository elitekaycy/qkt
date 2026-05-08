package com.qkt.cli.observe

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerTest {
    private val client = OkHttpClient()

    private fun server(
        ring: EventRing = EventRing(),
        statusProvider: () -> StatusSnapshot = { error("not implemented in this test") },
        running: () -> Boolean = { true },
        onStop: (Boolean) -> Unit = {},
    ): ObservabilityServer =
        ObservabilityServer(
            ring = ring,
            statusProvider = statusProvider,
            running = running,
            onStop = onStop,
            bind = "127.0.0.1",
            port = 0,
        )

    @Test
    fun `health returns 200 ok json when running`() {
        val s = server(running = { true })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/health").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            assertThat(resp.body!!.string()).contains("\"status\":\"ok\"")
        } finally {
            s.close()
        }
    }

    @Test
    fun `health returns 503 terminated when not running`() {
        val s = server(running = { false })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/health").build()).execute()
            assertThat(resp.code).isEqualTo(503)
            assertThat(resp.body!!.string()).contains("\"status\":\"terminated\"")
        } finally {
            s.close()
        }
    }

    @Test
    fun `health rejects POST with 405`() {
        val s = server(running = { true })
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/health")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(405)
        } finally {
            s.close()
        }
    }

    @Test
    fun `unknown route returns 404`() {
        val s = server()
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/nope").build()).execute()
            assertThat(resp.code).isEqualTo(404)
        } finally {
            s.close()
        }
    }
}
