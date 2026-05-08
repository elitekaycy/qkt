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
                    method == "GET" && path == "/list" -> handleList(ex, registry)
                    method == "POST" && path.startsWith("/stop/") -> handleStop(ex, registry, path)
                    method == "POST" && path == "/shutdown" -> handleShutdown(ex, shutdown)
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

    private fun handleList(
        ex: HttpExchange,
        registry: StrategyRegistry,
    ) {
        val now = Instant.now().toEpochMilli()
        val arr =
            registry.list().joinToString(separator = ",", prefix = "[", postfix = "]") { h ->
                val uptime = now - h.startedAt.toEpochMilli()
                val state = if (h.isRunning()) "running" else "stopped"
                """{"name":"${h.name}","port":${h.port},""" +
                    """"trades":${h.tradeCount},"uptimeMs":$uptime,"state":"$state"}"""
            }
        respond(ex, 200, arr)
    }

    private fun handleShutdown(
        ex: HttpExchange,
        shutdown: () -> Unit,
    ) {
        respond(ex, 202, """{"status":"accepted"}""")
        // Trigger asynchronously so the response can flush before the server closes.
        Thread {
            try {
                Thread.sleep(50)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            runCatching { shutdown() }
        }.apply {
            isDaemon = true
            start()
        }
    }

    private fun handleStop(
        ex: HttpExchange,
        registry: StrategyRegistry,
        path: String,
    ) {
        val name = path.removePrefix("/stop/").trim('/').ifBlank { null }
        if (name == null) {
            return respond(ex, 400, """{"error":"missing strategy name in path"}""")
        }
        val handle = registry.get(name)
        if (handle == null) {
            return respond(ex, 404, """{"error":"unknown strategy: $name"}""")
        }
        // ?flatten=true and ?timeout=<ms> are accepted; flatten is a no-op in 12c paper mode.
        val params = parseQuery(ex.requestURI.rawQuery)
        if (params.containsKey("timeout")) {
            val t = params["timeout"]?.toLongOrNull()
            if (t == null || t < 0) {
                return respond(ex, 400, """{"error":"invalid 'timeout' query param"}""")
            }
        }
        if (params.containsKey("flatten")) {
            val f = params["flatten"]
            if (f != "true" && f != "false") {
                return respond(ex, 400, """{"error":"invalid 'flatten' query param"}""")
            }
        }
        val trades = handle.tradeCount
        registry.stop(name)
        respond(ex, 200, """{"name":"$name","state":"stopped","trades":$trades}""")
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw
            .split('&')
            .mapNotNull { part ->
                val i = part.indexOf('=')
                if (i < 0) return@mapNotNull null
                part.substring(0, i) to part.substring(i + 1)
            }.toMap()
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
