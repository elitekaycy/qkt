package com.qkt.cli.daemon.routes

private val internalHttp = okhttp3.OkHttpClient()

/** The strategy's `/status` body, or `null` when it cannot be reached. */
internal fun fetchStrategyStatus(port: Int): String? = fetchStrategyEndpoint(port, "/status")

/**
 * GETs [endpoint] from the strategy observe server on [port]; `null` on any failure
 * or non-2xx answer.
 */
internal fun fetchStrategyEndpoint(
    port: Int,
    endpoint: String,
): String? =
    runCatching {
        val resp =
            internalHttp
                .newCall(
                    okhttp3.Request
                        .Builder()
                        .url("http://127.0.0.1:$port$endpoint")
                        .build(),
                ).execute()
        resp.use { r ->
            if (r.isSuccessful) r.body?.string() else null
        }
    }.getOrNull()
