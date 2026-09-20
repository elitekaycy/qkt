package com.qkt.cli.daemon

import java.nio.file.Files
import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class DeployCommandTest : DeployCommandFixture() {
    @Test
    fun `POST deploy returns name port state startedAt`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val file = tmp.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                .toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(200)
        val responseBody = resp.body!!.string()
        assertThat(responseBody).contains("\"name\":\"foo\"")
        assertThat(responseBody).contains("\"port\":")
        assertThat(responseBody).contains("\"state\":\"running\"")
    }

    @Test
    fun `POST deploy with bad body returns 400`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val body = """not-json""".toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }

    @Test
    fun `POST deploy with missing file field returns 400`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val body = """{"name":"foo"}""".toRequestBody("application/json".toMediaType())
        val resp =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        assertThat(resp.code).isEqualTo(400)
    }

    @Test
    fun `POST deploy with duplicate name returns 409`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val client = OkHttpClient()
        val file = tmp.resolve("foo.qkt").also { Files.writeString(it, "STRATEGY x VERSION 1") }
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                .toRequestBody("application/json".toMediaType())
        val first =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        first.close()
        val second =
            client
                .newCall(
                    Request
                        .Builder()
                        .url("http://127.0.0.1:${plane.boundPort}/deploy")
                        .post(
                            """{"file":"${file.toAbsolutePath()}","name":"foo"}"""
                                .toRequestBody("application/json".toMediaType()),
                        ).build(),
                ).execute()
        assertThat(second.code).isEqualTo(409)
    }

    @Test
    fun `ControlClient deploy round-trips through the daemon`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val plane = newPlane(tmp, stateDir)
        val controlClient = ControlClient(stateDir)
        val file = tmp.resolve("alpha.qkt").also { Files.writeString(it, "STRATEGY a VERSION 1") }
        val body = controlClient.deploy("alpha", file)
        assertThat(body).contains("\"name\":\"alpha\"")
        assertThat(plane.boundPort).isGreaterThan(0)
    }

    @Test
    fun `ControlClient raises when no daemon is running`(
        @TempDir tmp: Path,
    ) {
        val stateDir = StateDir.resolve(tmp.toString())
        val client = ControlClient(stateDir)
        assertThat(
            runCatching { client.health() }.exceptionOrNull(),
        ).isInstanceOf(ControlClient.NoDaemonRunningException::class.java)
    }
}
