package com.qkt.cli.observe

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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

    private fun fixtureSnapshot(): StatusSnapshot =
        StatusSnapshot(
            strategy = "demo",
            version = 1,
            uptimeMs = 100L,
            startedAt = "2026-05-08T14:31:14Z",
            equity = BigDecimal("9997.66"),
            balance = BigDecimal("10000.00"),
            realized = BigDecimal("-2.34"),
            unrealized = BigDecimal("0.00"),
            positions = listOf(PositionDto("BTCUSDT", BigDecimal("0.001"), BigDecimal("68234.50"))),
            lastTrade = null,
        )

    @Test
    fun `status returns serialized snapshot as JSON`() {
        val s = server(statusProvider = { fixtureSnapshot() })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/status").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            assertThat(resp.header("Content-Type")).contains("application/json")
            val body = resp.body!!.string()
            assertThat(body).contains("\"strategy\":\"demo\"")
            assertThat(body).contains("\"equity\":9997.66")
            assertThat(body).contains("\"realized\":-2.34")
        } finally {
            s.close()
        }
    }

    @Test
    fun `status rejects POST with 405`() {
        val s = server(statusProvider = { fixtureSnapshot() })
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/status")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(405)
        } finally {
            s.close()
        }
    }

    @Test
    fun `status returns 500 when provider throws`() {
        val s = server(statusProvider = { error("synthetic failure") })
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/status").build()).execute()
            assertThat(resp.code).isEqualTo(500)
        } finally {
            s.close()
        }
    }

    @Test
    fun `logs returns empty array on empty ring`() {
        val s = server()
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/logs").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            assertThat(resp.body!!.string().trim()).isEqualTo("[]")
        } finally {
            s.close()
        }
    }

    @Test
    fun `logs returns ring entries with default limit`() {
        val ring = EventRing()
        repeat(5) { i -> ring.append("trade", buildJsonObject { put("v", i.toString()) }) }
        val s = server(ring = ring)
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/logs").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            val arr = Json.parseToJsonElement(resp.body!!.string()) as JsonArray
            assertThat(arr).hasSize(5)
            val first = arr[0] as JsonObject
            assertThat(first["kind"]!!.jsonPrimitive.content).isEqualTo("trade")
            assertThat(first["payload"]!!.jsonObject["v"]!!.jsonPrimitive.content).isEqualTo("0")
        } finally {
            s.close()
        }
    }

    @Test
    fun `logs respects limit query param`() {
        val ring = EventRing()
        repeat(5) { i -> ring.append("trade", buildJsonObject { put("v", i.toString()) }) }
        val s = server(ring = ring)
        s.start()
        try {
            val resp =
                client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/logs?limit=3").build()).execute()
            assertThat(resp.code).isEqualTo(200)
            val arr = Json.parseToJsonElement(resp.body!!.string()) as JsonArray
            assertThat(arr).hasSize(3)
            assertThat((arr[0] as JsonObject)["payload"]!!.jsonObject["v"]!!.jsonPrimitive.content).isEqualTo("2")
        } finally {
            s.close()
        }
    }

    @Test
    fun `logs filters by since query param`() {
        val ring = EventRing()
        ring.append("trade", buildJsonObject { put("v", "early") })
        Thread.sleep(5)
        val cutoff = System.currentTimeMillis()
        Thread.sleep(5)
        ring.append("trade", buildJsonObject { put("v", "late") })
        val s = server(ring = ring)
        s.start()
        try {
            val resp =
                client
                    .newCall(
                        Request.Builder().url("http://127.0.0.1:${s.boundPort}/logs?since=$cutoff").build(),
                    ).execute()
            assertThat(resp.code).isEqualTo(200)
            val arr = Json.parseToJsonElement(resp.body!!.string()) as JsonArray
            assertThat(arr).hasSize(1)
            assertThat((arr[0] as JsonObject)["payload"]!!.jsonObject["v"]!!.jsonPrimitive.content).isEqualTo("late")
        } finally {
            s.close()
        }
    }

    @Test
    fun `logs returns 400 on bad query param`() {
        val s = server()
        s.start()
        try {
            val resp =
                client
                    .newCall(
                        Request.Builder().url("http://127.0.0.1:${s.boundPort}/logs?since=oops").build(),
                    ).execute()
            assertThat(resp.code).isEqualTo(400)
        } finally {
            s.close()
        }
    }

    @Test
    fun `stop POST returns 202 and invokes onStop with flatten false`() {
        val captured =
            java.util.concurrent.atomic
                .AtomicReference<Boolean?>(null)
        val s = server(onStop = { captured.set(it) })
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/stop")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(202)
            val body = resp.body!!.string()
            assertThat(body).contains("\"status\":\"accepted\"")
            assertThat(body).contains("\"action\":\"graceful_shutdown\"")
            assertThat(captured.get()).isFalse()
        } finally {
            s.close()
        }
    }

    @Test
    fun `stop POST with flatten true invokes onStop with flatten true`() {
        val captured =
            java.util.concurrent.atomic
                .AtomicReference<Boolean?>(null)
        val s = server(onStop = { captured.set(it) })
        s.start()
        try {
            val req =
                Request
                    .Builder()
                    .url("http://127.0.0.1:${s.boundPort}/stop?flatten=true")
                    .post(ByteArray(0).toRequestBody(null))
                    .build()
            val resp = client.newCall(req).execute()
            assertThat(resp.code).isEqualTo(202)
            assertThat(captured.get()).isTrue()
        } finally {
            s.close()
        }
    }

    @Test
    fun `stop GET returns 405`() {
        val s = server(onStop = {})
        s.start()
        try {
            val resp = client.newCall(Request.Builder().url("http://127.0.0.1:${s.boundPort}/stop").build()).execute()
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
