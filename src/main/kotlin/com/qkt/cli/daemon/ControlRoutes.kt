package com.qkt.cli.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.time.Instant

object ControlRoutes {
    fun dispatch(
        registry: StrategyRegistry,
        startedAt: Instant,
        shutdown: () -> Unit,
    ): HttpHandler =
        HttpHandler { ex ->
            val path = ex.requestURI.path
            val method = ex.requestMethod
            try {
                when {
                    method == "GET" && path == "/health" -> handleHealth(ex, registry, startedAt)
                    else -> respond(ex, 404, """{"error":"not found"}""")
                }
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
                respond(ex, 500, """{"error":"$msg"}""")
            }
        }

    private fun handleHealth(
        ex: HttpExchange,
        registry: StrategyRegistry,
        startedAt: Instant,
    ) {
        val uptimeMs = Instant.now().toEpochMilli() - startedAt.toEpochMilli()
        val count = registry.list().size
        respond(ex, 200, """{"status":"ok","strategies":$count,"uptimeMs":$uptimeMs}""")
    }

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
}
