package com.qkt.cli.daemon

import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StopRouteFlattenTest : StopRouteFixture() {
    @Test
    fun `POST stop with flatten true on top-level strategy calls live flatten`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?flatten=true&timeout=2000")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(flattens("foo")).isEqualTo(1)
        // The stop flatten, so the session clears its rule edges and a restart can re-enter.
        assertThat(stopFlattens("foo")).isEqualTo(1)
    }

    @Test
    fun `a flatten that did not end flat answers not_flat, naming what is held and why`(
        @TempDir tmp: Path,
    ) {
        stopFlattenResult =
            com.qkt.app.FlattenResult(false, listOf("-0.001 of D:BTC_4NOV26"), "still holds it: stream stopped")
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val body = ControlClient(stateDir, explicitPort = plane.boundPort).stop("foo", flatten = true)

        val answer = Json.parseToJsonElement(body).jsonObject
        assertThat(answer["state"]?.jsonPrimitive?.content).isEqualTo("not_flat")
        assertThat(answer["flat"]?.jsonPrimitive?.content).isEqualTo("false")
        assertThat(answer["held"].toString()).contains("-0.001 of D:BTC_4NOV26")
        assertThat(answer["detail"]?.jsonPrimitive?.content).contains("stream stopped")
    }

    @Test
    fun `a flatten that ended flat answers stopped`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val body = ControlClient(stateDir, explicitPort = plane.boundPort).stop("foo", flatten = true)

        val answer = Json.parseToJsonElement(body).jsonObject
        assertThat(answer["state"]?.jsonPrimitive?.content).isEqualTo("stopped")
        assertThat(answer["flat"]?.jsonPrimitive?.content).isEqualTo("true")
    }

    @Test
    fun `POST stop without flatten on top-level strategy does not call live flatten`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(flattens("foo")).isEqualTo(0)
    }

    @Test
    fun `POST stop with flatten false on top-level strategy does not call live flatten`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (registry, plane) = newPlane(stateDir)
        registry.deploy("foo", tmp.resolve("foo.qkt"))
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?flatten=false")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        assertThat(flattens("foo")).isEqualTo(0)
    }
}
