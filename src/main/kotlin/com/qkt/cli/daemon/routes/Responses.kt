package com.qkt.cli.daemon.routes

import com.sun.net.httpserver.HttpExchange

/** Writes [body] as a Prometheus text-exposition response with status [code]. */
internal fun respondText(
    ex: HttpExchange,
    code: Int,
    body: String,
) {
    ex.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}

/** Writes [body] as an `application/json` response with status [code]. */
internal fun respond(
    ex: HttpExchange,
    code: Int,
    body: String,
) {
    ex.responseHeaders.add("Content-Type", "application/json")
    val bytes = body.toByteArray(Charsets.UTF_8)
    ex.sendResponseHeaders(code, bytes.size.toLong())
    ex.responseBody.use { it.write(bytes) }
}
