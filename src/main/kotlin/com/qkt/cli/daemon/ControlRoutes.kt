package com.qkt.cli.daemon

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ControlRoutes {
    private val json = Json { encodeDefaults = true }

    fun dispatch(
        registry: StrategyRegistry,
        startedAt: Instant,
        stateDir: StateDir?,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer? = null,
        shutdown: () -> Unit,
        notifierMetrics: com.qkt.notify.NotifierMetrics? = null,
        prometheusMetricsEnabled: Boolean = true,
    ): HttpHandler =
        HttpHandler { ex ->
            val path = ex.requestURI.path
            val method = ex.requestMethod
            try {
                when {
                    method == "GET" && path == "/health" -> handleHealth(ex, registry, startedAt)
                    method == "POST" && path == "/deploy" -> handleDeploy(ex, registry, portfolioDeployer)
                    method == "GET" && path == "/list" -> handleList(ex, registry)
                    method == "POST" && path.startsWith("/stop/") -> handleStop(ex, registry, path)
                    method == "POST" && path.startsWith("/start/") -> handleStart(ex, registry, path)
                    method == "POST" && path == "/shutdown" -> handleShutdown(ex, shutdown)
                    method == "GET" && path.startsWith("/logs/") ->
                        handleLogs(ex, registry, stateDir, path)
                    method == "GET" && path == "/status" -> handleStatusAll(ex, registry)
                    method == "GET" && path.startsWith("/status/") -> handleStatusOne(ex, registry, path)
                    method == "GET" && path == "/latency" -> handleLatencyAll(ex, registry)
                    method == "GET" && path == "/metrics" && prometheusMetricsEnabled ->
                        handleMetrics(ex, registry, startedAt, notifierMetrics)
                    else -> respond(ex, 404, """{"error":"not found"}""")
                }
            } catch (e: Exception) {
                val msg = (e.message ?: e.javaClass.simpleName).replace("\"", "'")
                respond(ex, 500, """{"error":"$msg"}""")
            }
        }

    /**
     * `GET /metrics` — Prometheus text exposition format. Currently covers:
     * - Notifier counters (sent/dropped/failed/rateLimitHits) + degradedMode gauge.
     * - Daemon uptime + strategies-running gauge.
     * - Per-strategy trade counter.
     *
     * Latency exposition (per-strategy + per-stage from [com.qkt.observability.LatencyRegistry])
     * is deferred to a follow-up — needs design discussion on label cardinality and quantile
     * shape (summary vs histogram). See #79.
     */
    private fun handleMetrics(
        ex: HttpExchange,
        registry: StrategyRegistry,
        startedAt: Instant,
        notifierMetrics: com.qkt.notify.NotifierMetrics?,
    ) {
        val now = Instant.now()
        val out = PrometheusFormat()

        out.gauge(
            "qkt_daemon_uptime_seconds",
            "Seconds the daemon has been up",
            listOf(
                PrometheusFormat.Sample(
                    value = ((now.toEpochMilli() - startedAt.toEpochMilli()) / 1_000L).toString(),
                ),
            ),
        )
        out.gauge(
            "qkt_strategies_running",
            "Count of strategies currently in running state",
            listOf(
                PrometheusFormat.Sample(
                    value = registry.list().count { it.isRunning() }.toString(),
                ),
            ),
        )
        out.counter(
            "qkt_strategy_trades_total",
            "Total trades executed per strategy since daemon start",
            registry.list().map { h ->
                PrometheusFormat.Sample(
                    labels = mapOf("strategy" to h.name),
                    value = h.tradeCount.toString(),
                )
            },
        )

        if (notifierMetrics != null) {
            out.counter(
                "qkt_notifier_sent_total",
                "Notifications successfully sent",
                listOf(PrometheusFormat.Sample(value = notifierMetrics.sent.toString())),
            )
            out.counter(
                "qkt_notifier_dropped_total",
                "Notifications dropped without an attempt (queue full, etc)",
                listOf(PrometheusFormat.Sample(value = notifierMetrics.dropped.toString())),
            )
            out.counter(
                "qkt_notifier_failed_total",
                "Notification send attempts that failed",
                listOf(PrometheusFormat.Sample(value = notifierMetrics.failed.toString())),
            )
            out.counter(
                "qkt_notifier_rate_limit_hits_total",
                "Number of rate-limit responses observed from the notification provider",
                listOf(PrometheusFormat.Sample(value = notifierMetrics.rateLimitHits.toString())),
            )
            out.gauge(
                "qkt_notifier_degraded_mode",
                "1 when the notifier is in degraded mode (giving up retries), 0 otherwise",
                listOf(PrometheusFormat.Sample(value = if (notifierMetrics.degradedMode) "1" else "0")),
            )
        }

        respondText(ex, 200, out.toString())
    }

    private fun respondText(
        ex: HttpExchange,
        code: Int,
        body: String,
    ) {
        ex.responseHeaders.add("Content-Type", "text/plain; version=0.0.4; charset=utf-8")
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.sendResponseHeaders(code, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
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
        val rows = mutableListOf<String>()
        for (record in registry.listPortfolios()) {
            val uptime = now - record.startedAt.toEpochMilli()
            val state = if (record.supervisor.running) "running" else "stopped"
            val aliases = record.children.mapNotNull { it.childMeta?.alias }
            val aliasJson = aliases.joinToString(",", "[", "]") { "\"$it\"" }
            rows.add(
                """{"name":"${record.name}","kind":"portfolio","childAliases":$aliasJson,""" +
                    """"uptimeMs":$uptime,"state":"$state"}""",
            )
        }
        for (h in registry.list()) {
            val uptime = now - h.startedAt.toEpochMilli()
            val state = if (h.isRunning()) "running" else "stopped"
            val streamBrokersJson = renderStreamBrokers(h.live.streamBrokers())
            val meta = h.childMeta
            if (meta != null) {
                val gateState =
                    when {
                        meta.operatorStop.get() -> "operator_stopped"
                        meta.gateActive.get() -> "active"
                        else -> "idle"
                    }
                rows.add(
                    """{"name":"${h.name}","kind":"child","parent":"${meta.parent}",""" +
                        """"port":${h.port},"trades":${h.tradeCount},""" +
                        """"uptimeMs":$uptime,"state":"$state","gateState":"$gateState",""" +
                        """"streamBrokers":$streamBrokersJson}""",
                )
            } else {
                rows.add(
                    """{"name":"${h.name}","kind":"strategy","port":${h.port},""" +
                        """"trades":${h.tradeCount},"uptimeMs":$uptime,"state":"$state",""" +
                        """"streamBrokers":$streamBrokersJson}""",
                )
            }
        }
        respond(ex, 200, rows.joinToString(",", "[", "]"))
    }

    private fun renderStreamBrokers(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        return map.entries.joinToString(",", "{", "}") { (k, v) -> "\"$k\":\"$v\"" }
    }

    private val internalHttp = okhttp3.OkHttpClient()

    private fun handleStatusOne(
        ex: HttpExchange,
        registry: StrategyRegistry,
        path: String,
    ) {
        val name = path.removePrefix("/status/").trim('/').ifBlank { null }
        if (name == null) return respond(ex, 400, """{"error":"missing name in path"}""")
        registry.getPortfolio(name)?.let { record ->
            return respond(ex, 200, composePortfolioStatus(registry, record))
        }
        val handle =
            registry.get(name)
                ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
        val body =
            fetchStrategyStatus(handle.port)
                ?: return respond(ex, 502, """{"error":"strategy /status unreachable"}""")
        if (handle.childMeta != null) {
            respond(ex, 200, augmentChildStatus(body, handle))
        } else {
            respond(ex, 200, body)
        }
    }

    private fun augmentChildStatus(
        body: String,
        handle: StrategyHandle,
    ): String {
        val obj = json.parseToJsonElement(body).jsonObject
        val meta = handle.childMeta ?: return body
        val updated =
            kotlinx.serialization.json.buildJsonObject {
                for ((k, v) in obj) put(k, v)
                put("kind", kotlinx.serialization.json.JsonPrimitive("child"))
                put("parent", kotlinx.serialization.json.JsonPrimitive(meta.parent))
                put("alias", kotlinx.serialization.json.JsonPrimitive(meta.alias))
                put("gateActive", kotlinx.serialization.json.JsonPrimitive(meta.gateActive.get()))
                put("operatorStop", kotlinx.serialization.json.JsonPrimitive(meta.operatorStop.get()))
                put("hold", kotlinx.serialization.json.JsonPrimitive(meta.hold))
            }
        return updated.toString()
    }

    private fun composePortfolioStatus(
        registry: StrategyRegistry,
        record: PortfolioRecord,
    ): String {
        val now = System.currentTimeMillis()
        val children = registry.childrenOf(record.name)
        var realized = java.math.BigDecimal.ZERO
        var unrealized = java.math.BigDecimal.ZERO
        var equity = java.math.BigDecimal.ZERO
        var balance = java.math.BigDecimal.ZERO
        val childRows = mutableListOf<kotlinx.serialization.json.JsonObject>()
        for (c in children) {
            val meta = c.childMeta ?: continue
            val raw = fetchStrategyStatus(c.port) ?: continue
            val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: continue
            realized =
                realized +
                (obj["realized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            unrealized =
                unrealized +
                (obj["unrealized"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            equity =
                equity +
                (obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            balance =
                balance +
                (obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO)
            childRows.add(
                kotlinx.serialization.json.buildJsonObject {
                    put("alias", kotlinx.serialization.json.JsonPrimitive(meta.alias))
                    put("name", kotlinx.serialization.json.JsonPrimitive(c.name))
                    put("port", kotlinx.serialization.json.JsonPrimitive(c.port))
                    put("gateActive", kotlinx.serialization.json.JsonPrimitive(meta.gateActive.get()))
                    put("operatorStop", kotlinx.serialization.json.JsonPrimitive(meta.operatorStop.get()))
                    put("hold", kotlinx.serialization.json.JsonPrimitive(meta.hold))
                    put("trades", kotlinx.serialization.json.JsonPrimitive(c.tradeCount))
                    put("realized", obj["realized"] ?: kotlinx.serialization.json.JsonPrimitive("0"))
                    put("unrealized", obj["unrealized"] ?: kotlinx.serialization.json.JsonPrimitive("0"))
                },
            )
        }
        return kotlinx.serialization.json
            .buildJsonObject {
                put("name", kotlinx.serialization.json.JsonPrimitive(record.name))
                put("kind", kotlinx.serialization.json.JsonPrimitive("portfolio"))
                put("version", kotlinx.serialization.json.JsonPrimitive(record.version))
                put("startedAt", kotlinx.serialization.json.JsonPrimitive(record.startedAt.toString()))
                put("uptimeMs", kotlinx.serialization.json.JsonPrimitive(now - record.startedAt.toEpochMilli()))
                put("supervisorRunning", kotlinx.serialization.json.JsonPrimitive(record.supervisor.running))
                put("equity", kotlinx.serialization.json.JsonPrimitive(equity.toPlainString()))
                put("balance", kotlinx.serialization.json.JsonPrimitive(balance.toPlainString()))
                put("realized", kotlinx.serialization.json.JsonPrimitive(realized.toPlainString()))
                put("unrealized", kotlinx.serialization.json.JsonPrimitive(unrealized.toPlainString()))
                put("children", kotlinx.serialization.json.JsonArray(childRows))
            }.toString()
    }

    private fun handleStatusAll(
        ex: HttpExchange,
        registry: StrategyRegistry,
    ) {
        val portfolioParts = registry.listPortfolios().map { composePortfolioStatus(registry, it) }
        val strategyParts =
            registry.list().filter { it.childMeta == null }.mapNotNull { h ->
                fetchStrategyStatus(h.port)
            }
        val all = portfolioParts + strategyParts
        respond(ex, 200, all.joinToString(separator = ",", prefix = "[", postfix = "]"))
    }

    private fun fetchStrategyStatus(port: Int): String? = fetchStrategyEndpoint(port, "/status")

    private fun fetchStrategyEndpoint(
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

    /**
     * `GET /latency` — aggregates per-strategy `/latency` responses into a top-level
     * `{ "<strategyName>": <perStrategyLatencyJson>, ... }`. Each entry's body is whatever
     * the strategy's [com.qkt.cli.observe.Routes.latency] handler returned (already JSON).
     * Strategies that can't be reached are omitted from the aggregate.
     */
    private fun handleLatencyAll(
        ex: HttpExchange,
        registry: StrategyRegistry,
    ) {
        val parts =
            registry.list().mapNotNull { h ->
                val body = fetchStrategyEndpoint(h.port, "/latency") ?: return@mapNotNull null
                """"${h.name}":$body"""
            }
        respond(ex, 200, parts.joinToString(separator = ",", prefix = "{", postfix = "}"))
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
            return respond(ex, 400, """{"error":"missing name in path"}""")
        }
        val params = parseQuery(ex.requestURI.rawQuery)
        if (params.containsKey("timeout")) {
            val t = params["timeout"]?.toLongOrNull()
            if (t == null || t < 0) {
                return respond(ex, 400, """{"error":"invalid 'timeout' query param"}""")
            }
        }
        val flattenOverride: Boolean? =
            when (val raw = params["flatten"]) {
                null -> null
                "true" -> true
                "false" -> false
                else -> return respond(ex, 400, """{"error":"invalid 'flatten' query param"}""")
            }

        registry.getPortfolio(name)?.let { record ->
            record.supervisor.stop()
            var totalTrades = 0
            for (child in record.children) {
                val meta = child.childMeta
                val shouldFlatten = flattenOverride ?: (meta != null && !meta.hold)
                if (shouldFlatten) {
                    runCatching { child.live.flatten() }
                }
                totalTrades += child.tradeCount
            }
            registry.removePortfolio(name)
            for (child in record.children) runCatching { child.close() }
            return respond(ex, 200, """{"name":"$name","state":"stopped","trades":$totalTrades}""")
        }

        val handle =
            registry.get(name)
                ?: return respond(ex, 404, """{"error":"unknown name: $name"}""")
        val meta = handle.childMeta
        if (meta != null) {
            meta.operatorStop.set(true)
            meta.gateActive.set(false)
            val shouldFlatten = flattenOverride ?: !meta.hold
            if (shouldFlatten) runCatching { handle.live.flatten() }
            return respond(
                ex,
                200,
                """{"name":"$name","state":"operator_stopped","trades":${handle.tradeCount}}""",
            )
        }
        val trades = handle.tradeCount
        if (flattenOverride == true) runCatching { handle.live.flatten() }
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
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer?,
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
        if (name.contains('/')) {
            return respond(ex, 400, """{"error":"top-level name must not contain '/': $name"}""")
        }
        val path = Path.of(file)
        if (!Files.exists(path)) {
            return respond(ex, 400, """{"error":"file not found: $file"}""")
        }
        val params = parseQuery(ex.requestURI.rawQuery)
        val ignoreMismatches = params["reconcile"] == "ignore-mismatches"

        val parsed =
            when (
                val r =
                    com.qkt.dsl.parse.Dsl
                        .parseFileAny(path)
            ) {
                is com.qkt.dsl.parse.ParseResult.Success -> r.value
                is com.qkt.dsl.parse.ParseResult.Failure -> {
                    val msg = r.errors.joinToString(";") { it.message }.replace("\"", "'")
                    return respond(ex, 400, """{"error":"parse failed: $msg"}""")
                }
            }

        when (parsed) {
            is com.qkt.dsl.parse.ParsedFile.StrategyFile -> {
                val handle =
                    try {
                        registry.deploy(name, path, ignoreMismatches)
                    } catch (e: com.qkt.app.ReconcileException) {
                        val msg = (e.message ?: "reconcile mismatch").replace("\"", "'")
                        return respond(ex, 409, """{"error":"$msg","kind":"reconcile-mismatch"}""")
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
                    """{"name":"${handle.name}","kind":"strategy","port":${handle.port},""" +
                        """"state":"running","startedAt":"${handle.startedAt}"}""",
                )
            }
            is com.qkt.dsl.parse.ParsedFile.PortfolioFile -> {
                if (portfolioDeployer == null) {
                    return respond(ex, 501, """{"error":"portfolio deploy not configured on this daemon"}""")
                }
                val record =
                    try {
                        val compiled =
                            com.qkt.dsl.portfolio.PortfolioLoader
                                .load(path)
                        val record = portfolioDeployer.deploy(name, compiled)
                        registry.registerPortfolio(record)
                        record
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
                val childrenJson =
                    record.children.joinToString(",", "[", "]") { c ->
                        """{"alias":"${c.childMeta!!.alias}","name":"${c.name}","port":${c.port},"hold":${c.childMeta.hold}}"""
                    }
                respond(
                    ex,
                    200,
                    """{"name":"${record.name}","kind":"portfolio","state":"running",""" +
                        """"startedAt":"${record.startedAt}","children":$childrenJson}""",
                )
            }
        }
    }

    private fun handleStart(
        ex: HttpExchange,
        registry: StrategyRegistry,
        path: String,
    ) {
        val name =
            path.removePrefix("/start/").trim('/').ifBlank { null }
                ?: return respond(ex, 400, """{"error":"missing name"}""")
        val handle = registry.get(name)
        if (handle != null && handle.childMeta != null) {
            handle.childMeta.operatorStop.set(false)
            return respond(ex, 200, """{"name":"$name","state":"resumed"}""")
        }
        if (handle != null) {
            return respond(ex, 400, """{"error":"strategy '$name' has no paused state"}""")
        }
        if (registry.getPortfolio(name) != null) {
            return respond(ex, 400, """{"error":"portfolio '$name' cannot be started; use deploy"}""")
        }
        respond(ex, 404, """{"error":"unknown name: $name"}""")
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
