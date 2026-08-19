package com.qkt.cli.daemon

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

open class ControlClient(
    private val stateDir: StateDir,
    private val http: OkHttpClient = defaultHttp(),
    private val explicitPort: Int? = null,
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
            explicitPort
                ?: stateDir.readControlPort()
                ?: throw NoDaemonRunningException(
                    "no daemon running (no control.port file at ${stateDir.controlPortFile})",
                )
        return "http://127.0.0.1:$port"
    }

    private fun authenticatedRequest(url: String): Request.Builder =
        Request.Builder().url(url).also { builder ->
            ControlToken.forClient(stateDir)?.let { builder.header("Authorization", "Bearer ${it.value}") }
        }

    open fun metrics(): String {
        val resp =
            http.newCall(Request.Builder().url("${baseUrl()}/metrics").build()).execute()
        return readOrThrow(resp)
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
                    authenticatedRequest("${baseUrl()}/shutdown")
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
                    authenticatedRequest("${baseUrl()}/stop/$name$q")
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    fun start(name: String): String {
        val resp =
            http
                .newCall(
                    authenticatedRequest("${baseUrl()}/start/$name")
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
                    authenticatedRequest(url)
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    open fun kill(
        name: String? = null,
        flatten: Boolean = false,
    ): String {
        val base = if (name == null) "${baseUrl()}/kill" else "${baseUrl()}/kill/$name"
        val url = if (flatten) "$base?flatten=true" else base
        val resp =
            http
                .newCall(
                    authenticatedRequest(url)
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    open fun reconcile(name: String): String {
        val resp =
            http
                .newCall(
                    Request
                        .Builder()
                        .url("${baseUrl()}/reconcile/$name")
                        .get()
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    open fun resume(name: String? = null): String {
        val url = if (name == null) "${baseUrl()}/resume" else "${baseUrl()}/resume/$name"
        val resp =
            http
                .newCall(
                    authenticatedRequest(url)
                        .post("".toRequestBody(JSON_MEDIA))
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    fun deploy(
        name: String,
        file: Path,
        ignoreMismatches: Boolean = false,
        waiver: String? = null,
        waiverReason: String? = null,
    ): String {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name"}"""
                .toRequestBody(JSON_MEDIA)
        val q =
            buildList {
                if (ignoreMismatches) add("reconcile" to "ignore-mismatches")
                if (!waiver.isNullOrBlank()) add("waive" to waiver)
                if (!waiverReason.isNullOrBlank()) add("reason" to waiverReason)
            }.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
                .let { if (it.isEmpty()) "" else "?$it" }
        val resp =
            http
                .newCall(
                    authenticatedRequest("${baseUrl()}/deploy$q")
                        .post(body)
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    /**
     * Ask the daemon to validate and replace a deployed strategy or portfolio under its current name.
     */
    open fun resync(
        name: String,
        file: Path,
        dryRun: Boolean = false,
        ignoreMismatches: Boolean = false,
        waiver: String? = null,
        waiverReason: String? = null,
    ): String {
        val body =
            """{"file":"${file.toAbsolutePath()}","name":"$name","dryRun":$dryRun}"""
                .toRequestBody(JSON_MEDIA)
        val q =
            buildList {
                if (ignoreMismatches) add("reconcile" to "ignore-mismatches")
                if (!waiver.isNullOrBlank()) add("waive" to waiver)
                if (!waiverReason.isNullOrBlank()) add("reason" to waiverReason)
            }.joinToString("&") { (k, v) -> "${urlEncode(k)}=${urlEncode(v)}" }
                .let { if (it.isEmpty()) "" else "?$it" }
        val resp =
            http
                .newCall(
                    authenticatedRequest("${baseUrl()}/resync$q")
                        .post(body)
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    private fun urlEncode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private fun readOrThrow(resp: Response): String {
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw DaemonError(resp.code, body)
        return body
    }

    companion object {
        private val JSON_MEDIA = "application/json".toMediaType()

        /**
         * Control calls are synchronous: deploy/resync of a multi-child portfolio holds one
         * request open for minutes while the daemon swaps sessions. The stock 10s read timeout
         * failed the CLI mid-operation while the daemon completed anyway, so reads wait up to
         * 30 minutes; connecting to a dead daemon still fails fast.
         */
        fun defaultHttp(): OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(java.time.Duration.ofSeconds(10))
                .readTimeout(java.time.Duration.ofMinutes(30))
                .build()
    }
}
