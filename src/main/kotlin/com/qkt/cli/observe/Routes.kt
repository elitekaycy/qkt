package com.qkt.cli.observe

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

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

    fun logs(ring: EventRing): HttpHandler =
        HttpHandler { ex ->
            if (ex.requestMethod != "GET") {
                respond(ex, 405, """{"error":"method not allowed"}""")
                return@HttpHandler
            }
            val params = parseQuery(ex.requestURI.rawQuery)
            val since = params["since"]?.toLongOrNull()
            if (params["since"] != null && since == null) {
                respond(ex, 400, """{"error":"invalid 'since' query param"}""")
                return@HttpHandler
            }
            val limit = params["limit"]?.toIntOrNull()
            if (params["limit"] != null && (limit == null || limit < 1)) {
                respond(ex, 400, """{"error":"invalid 'limit' query param"}""")
                return@HttpHandler
            }
            val entries = ring.snapshot(since = since ?: 0L, limit = limit ?: 1000)
            val arr =
                JsonArray(
                    entries.map { e ->
                        buildJsonObject {
                            put("ts", JsonPrimitive(e.ts))
                            put("kind", JsonPrimitive(e.kind))
                            put("payload", e.payload)
                        }
                    },
                )
            respond(ex, 200, json.encodeToString(JsonArray.serializer(), arr))
        }

    fun events(ring: EventRing): HttpHandler =
        HttpHandler { ex ->
            if (ex.requestMethod != "GET") {
                respond(ex, 405, """{"error":"method not allowed"}""")
                return@HttpHandler
            }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.responseHeaders.add("Cache-Control", "no-cache")
            ex.responseHeaders.add("Connection", "keep-alive")
            ex.responseHeaders.add("Access-Control-Allow-Origin", "*")
            ex.sendResponseHeaders(200, 0)
            val out = ex.responseBody
            val alive =
                java.util.concurrent.atomic
                    .AtomicBoolean(true)
            val writeLock = Any()

            fun writeFrame(bytes: ByteArray): Boolean =
                try {
                    synchronized(writeLock) {
                        out.write(bytes)
                        out.flush()
                    }
                    true
                } catch (_: java.io.IOException) {
                    alive.set(false)
                    false
                }
            // Immediate prelude so the client unblocks on response start
            if (!writeFrame(": connected\n\n".toByteArray(Charsets.UTF_8))) {
                runCatching { out.close() }
                return@HttpHandler
            }
            val sub =
                ring.subscribe { entry ->
                    if (!alive.get()) return@subscribe
                    val payload =
                        json.encodeToString(
                            kotlinx.serialization.json.JsonObject
                                .serializer(),
                            entry.payload,
                        )
                    writeFrame("event: ${entry.kind}\ndata: $payload\n\n".toByteArray(Charsets.UTF_8))
                }
            try {
                while (alive.get()) {
                    Thread.sleep(15_000)
                    if (!writeFrame(": keep-alive\n\n".toByteArray(Charsets.UTF_8))) break
                }
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            } finally {
                sub.close()
                runCatching { out.close() }
            }
        }

    fun stop(onStop: (Boolean) -> Unit): HttpHandler = HttpHandler { _ -> TODO("Task 7") }

    internal fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw
            .split('&')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i < 0) return@mapNotNull null
                part.substring(0, i) to part.substring(i + 1)
            }.toMap()
    }

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
