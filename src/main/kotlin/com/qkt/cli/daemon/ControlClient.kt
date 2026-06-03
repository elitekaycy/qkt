package com.qkt.cli.daemon

import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

open class ControlClient(
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

    open fun health(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/health").build()).execute()
        return readOrThrow(resp)
    }

    open fun list(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/list").build()).execute()
        return readOrThrow(resp)
    }

    open fun status(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/status" else "${baseUrl()}/status/$name"
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        return readOrThrow(resp)
    }

    open fun latency(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/latency").build()).execute()
        return readOrThrow(resp)
    }

    fun logs(
        name: String,
        lines: Int? = null,
        since: String? = null,
        follow: Boolean = false,
    ): okhttp3.Response {
        val q =
            buildList {
                if (lines != null) add("lines=$lines")
                if (since != null) add("since=$since")
                if (follow) add("follow=true")
            }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        return http
            .newCall(Request.Builder().url("${baseUrl()}/logs/$name$q").build())
            .execute()
    }

    fun shutdown(): String {
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/shutdown")
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    fun stop(
        name: String,
        flatten: Boolean = false,
        timeoutMs: Long? = null,
    ): String {
        val q =
            buildList {
                if (flatten) add("flatten=true")
                if (timeoutMs != null) add("timeout=$timeoutMs")
            }.joinToString("&").let { if (it.isEmpty()) "" else "?$it" }
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/stop/$name$q")
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    fun start(name: String): String {
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/start/$name")
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    open fun halt(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/halt" else "${baseUrl()}/halt/$name"
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    open fun resume(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/resume" else "${baseUrl()}/resume/$name"
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url(url)
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    fun deploy(
        name: String,
        file: Path,
        ignoreMismatches: Boolean = false,
    ): String {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
                .toRequestBody(JSON_MEDIA)
        val q = if (ignoreMismatches) "?reconcile=ignore-mismatches" else ""
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/deploy$q")
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
