package com.qkt.cli.daemon

import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

class ControlClient(
    private val stateDir: StateDir,
    private val http: OkHttpClient = OkHttpClient(),
) {
    class NoDaemonRunningException(
        msg: String,
    ) : RuntimeException(msg)

    class DaemonError(
        val code: Int,
        val body: String,
    ) : RuntimeException("daemon returned $code: $body")

    private fun baseUrl(): String {
        val port =
            stateDir.readControlPort()
                ?: throw NoDaemonRunningException(
                    "no daemon running (no control.port file at ${stateDir.controlPortFile})",
                )
        return "http://127.0.0.1:$port"
    }

    fun health(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/health").build()).execute()
        return readOrThrow(resp)
    }

    fun list(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/list").build()).execute()
        return readOrThrow(resp)
    }

    fun deploy(
        name: String,
        file: Path,
    ): String {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
                .toRequestBody(JSON_MEDIA)
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/deploy")
                        .post(body)
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    private fun readOrThrow(resp: Response): String {
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw DaemonError(resp.code, body)
        return body
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()
    }
}
