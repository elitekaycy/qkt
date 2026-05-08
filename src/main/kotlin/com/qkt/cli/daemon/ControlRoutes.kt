package com.qkt.cli.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ControlRoutes {
    private val json = Json { encodeDefaults = true }

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
                    method == "POST" && path == "/deploy" -> handleDeploy(ex, registry)
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

    private fun handleDeploy(
        ex: HttpExchange,
        registry: StrategyRegistry,
    ) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val obj =
            try {
                json.parseToJsonElement(body) as? JsonObject
                    ?: return respond(ex, 400, """{"error":"body must be a JSON object"}""")
            } catch (_: Exception) {
                return respond(ex, 400, """{"error":"invalid JSON body"}""")
            }
        val file = obj["file"]?.jsonPrimitive?.contentOrNull
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        if (file.isNullOrBlank() || name.isNullOrBlank()) {
            return respond(ex, 400, """{"error":"missing 'file' or 'name'"}""")
        }
        val path = Path.of(file)
        if (!Files.exists(path)) {
            return respond(ex, 400, """{"error":"file not found: $file"}""")
        }
        val handle =
            try {
                registry.deploy(name, path)
            } catch (e: IllegalStateException) {
                val msg = (e.message ?: "conflict").replace("\"", "'")
                return respond(ex, 409, """{"error":"$msg"}""")
            } catch (e: IllegalArgumentException) {
                val msg = (e.message ?: "invalid").replace("\"", "'")
                return respond(ex, 400, """{"error":"$msg"}""")
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
                return respond(ex, 500, """{"error":"$msg"}""")
            }
        respond(
            ex,
            200,
            """{"name":"${handle.name}","port":${handle.port},""" +
                """"state":"running","startedAt":"${handle.startedAt}"}""",
        )
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
