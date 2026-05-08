package com.qkt.cli.observe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.Json

object Routes {
    private val json = Json { encodeDefaults = true }

    fun health(running: () -> Boolean): HttpHandler =
        HttpHandler { ex ->
            if (ex.requestMethod != "GET") {
                respond(ex, 405, """{"error":"method not allowed"}""")
                return@HttpHandler
            }
            val ok = running()
            val body = if (ok) """{"status":"ok"}""" else """{"status":"terminated"}"""
            respond(ex, if (ok) 200 else 503, body)
        }

    fun status(provider: () -> StatusSnapshot): HttpHandler =
        HttpHandler { ex ->
            if (ex.requestMethod != "GET") {
                respond(ex, 405, """{"error":"method not allowed"}""")
                return@HttpHandler
            }
            try {
                val snap = provider()
                val body = json.encodeToString(StatusSnapshot.serializer(), snap)
                respond(ex, 200, body)
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
                respond(ex, 500, """{"error":"$msg"}""")
            }
        }

    fun logs(ring: EventRing): HttpHandler = HttpHandler { _ -> TODO("Task 5") }

    fun events(ring: EventRing): HttpHandler = HttpHandler { _ -> TODO("Task 6") }

    fun stop(onStop: (Boolean) -> Unit): HttpHandler = HttpHandler { _ -> TODO("Task 7") }

    internal fun respond(
        ex: HttpExchange,
        code: Int,
        body: String,
    ) {
        ex.responseHeaders.add("Content-Type", "application/json")
        ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }
}
