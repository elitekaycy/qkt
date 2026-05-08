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
        stateDir: StateDir?,
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
                    method == "GET" && path.startsWith("/logs/") ->
                        handleLogs(ex, registry, stateDir, path)
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

    private fun handleLogs(
        ex: HttpExchange,
        registry: StrategyRegistry,
        stateDir: StateDir?,
        path: String,
    ) {
        val name = path.removePrefix("/logs/").trim('/').ifBlank { null }
        if (name == null) return respond(ex, 400, """{"error":"missing strategy name in path"}""")
        val handle = registry.get(name)
        val logFile =
            handle?.logFile
                ?: stateDir?.logFile(name)?.takeIf { Files.exists(it) }
                ?: return respond(ex, 404, """{"error":"unknown strategy: $name"}""")
        if (!Files.exists(logFile)) {
            // Stream nothing rather than 404; the strategy may simply have produced no logs yet.
            ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
            ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { /* no-op */ }
            return
        }
        val params = parseQuery(ex.requestURI.rawQuery)
        val lines = params["lines"]?.toIntOrNull() ?: 200
        if (lines < 0) return respond(ex, 400, """{"error":"invalid 'lines' query param"}""")
        val since: Instant? =
            params["since"]?.let {
                runCatching { Instant.parse(it) }.getOrNull()
                    ?: return respond(ex, 400, """{"error":"invalid 'since' query param"}""")
            }
        val follow = params["follow"] == "true"

        ex.responseHeaders.add("Content-Type", "text/plain; charset=utf-8")
        ex.responseHeaders.add("Cache-Control", "no-cache")
        ex.sendResponseHeaders(200, 0)
        val out = ex.responseBody
        val all = Files.readAllLines(logFile)
        val filtered =
            if (since != null) {
                all.filter { line -> parseTimestamp(line)?.isAfter(since.minusSeconds(1)) ?: true }
            } else {
                all
            }
        val tail = filtered.takeLast(lines)
        try {
            for (line in tail) {
                out.write((line + "\n").toByteArray(Charsets.UTF_8))
            }
            out.flush()
            if (follow) {
                var lastSize = Files.size(logFile)
                while (true) {
                    Thread.sleep(500)
                    val sz = Files.size(logFile)
                    if (sz < lastSize) {
                        // file truncated; reset
                        lastSize = 0
                    }
                    if (sz == lastSize) {
                        runCatching {
                            out.write("\n".toByteArray(Charsets.UTF_8))
                            out.flush()
                        }.onFailure { return }
                        continue
                    }
                    Files
                        .newInputStream(logFile)
                        .use { input ->
                            input.skip(lastSize)
                            val bytes = input.readBytes()
                            try {
                                out.write(bytes)
                                out.flush()
                            } catch (_: java.io.IOException) {
                                return
                            }
                        }
                    lastSize = sz
                }
            }
        } catch (_: java.io.IOException) {
            // client disconnected
        } finally {
            runCatching { out.close() }
        }
    }

    private fun parseTimestamp(line: String): Instant? {
        // Logback emits ISO8601 with `T` replaced by space; both are accepted by Instant.parse via fallback.
        val first = line.substringBefore(' ', missingDelimiterValue = "")
        if (first.isBlank()) return null
        return runCatching { Instant.parse(first.replace(',', '.')) }.getOrNull()
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
