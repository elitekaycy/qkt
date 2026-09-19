package com.qkt.cli.daemon

import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class StopRouteTest : StopRouteFixture() {
    @Test
    fun `POST stop name returns 200 with name state and trades`(
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
        val body = resp.body!!.string()
        assertThat(body).contains("\"name\":\"foo\"")
        assertThat(body).contains("\"state\":\"stopped\"")
        assertThat(body).contains("\"trades\":0")
        assertThat(registry.list()).isEmpty()
    }

    @Test
    fun `POST stop unknown returns 404`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val (_, plane) = newPlane(stateDir)
        val client = OkHttpClient()
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/stop/missing")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(404)
    }

    @Test
    fun `POST stop with invalid flatten returns 400`(
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
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?flatten=maybe")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }

    @Test
    fun `POST stop with invalid timeout returns 400`(
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
                        .url("http://127.0.0.1:${plane.boundPort}/stop/foo?timeout=oops")
                        .post("".toRequestBody("application/json".toMediaType()))
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }
}
