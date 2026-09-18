package com.qkt.cli.daemon

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

private val JSON_MEDIA = "application/json".toMediaType()

/**
 * HTTP plumbing for [ControlClient]: resolves the daemon's loopback port, sends reads without
 * credentials and mutations with the control bearer token, and turns non-2xx replies into
 * [ControlClient.DaemonError].
 */
internal class ControlTransport(
    private val stateDir: StateDir,
    private val http: OkHttpClient,
    private val explicitPort: Int?,
) {
    /** The daemon's base URL; throws [ControlClient.NoDaemonRunningException] when no port is known. */
    fun baseUrl(): String {
        val port =
            explicitPort
                ?: stateDir.readControlPort()
                ?: throw ControlClient.NoDaemonRunningException(
                    "no daemon running (no control.port file at ${stateDir.controlPortFile})",
                )
        return "http://127.0.0.1:$port"
    }

    /** Unauthenticated GET of [url], returning the body of a successful reply. */
    fun get(url: String): String {
        val resp = http.newCall(Request.Builder().url(url).build()).execute()
        return readOrThrow(resp)
    }

    /** Unauthenticated GET of [url], returning the raw response for the caller to stream and close. */
    fun open(url: String): Response = http.newCall(Request.Builder().url(url).build()).execute()

    /** Authenticated POST of [body] (empty JSON by default) to [url], returning the body of a successful reply. */
    fun post(
        url: String,
        body: RequestBody = "".toRequestBody(JSON_MEDIA),
    ): String {
        val resp =
            http
                .newCall(
                    authenticatedRequest(url)
                        .post(body)
                        .build(),
                ).execute()
        return readOrThrow(resp)
    }

    /** Authenticated POST of a JSON document. */
    fun postJson(
        url: String,
        json: String,
    ): String = post(url, json.toRequestBody(JSON_MEDIA))

    private fun authenticatedRequest(url: String): Request.Builder =
        Request.Builder().url(url).also { builder ->
            ControlToken.forClient(stateDir)?.let { builder.header("Authorization", "Bearer ${it.value}") }
        }

    private fun readOrThrow(resp: Response): String {
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw ControlClient.DaemonError(resp.code, body)
        return body
    }
}
