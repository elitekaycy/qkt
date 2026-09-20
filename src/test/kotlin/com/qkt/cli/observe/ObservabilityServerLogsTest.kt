package com.qkt.cli.observe

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Request
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class ObservabilityServerLogsTest : ObservabilityServerFixture() {
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
}
