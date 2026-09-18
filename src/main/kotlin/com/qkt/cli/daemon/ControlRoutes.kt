package com.qkt.cli.daemon

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionGateEvaluator
import com.qkt.cli.PromotionGateResult
import com.qkt.cli.PromotionJson
import com.qkt.cli.PromotionRecord
import com.qkt.cli.PromotionState
import com.qkt.cli.PromotionWaiver
import com.qkt.cli.daemon.routes.handleHalt
import com.qkt.cli.daemon.routes.handleHealth
import com.qkt.cli.daemon.routes.handleKill
import com.qkt.cli.daemon.routes.handleLatencyAll
import com.qkt.cli.daemon.routes.handleList
import com.qkt.cli.daemon.routes.handleLogs
import com.qkt.cli.daemon.routes.handleMetrics
import com.qkt.cli.daemon.routes.handleReconcile
import com.qkt.cli.daemon.routes.handleResume
import com.qkt.cli.daemon.routes.handleShutdown
import com.qkt.cli.daemon.routes.handleStart
import com.qkt.cli.daemon.routes.handleStatusAll
import com.qkt.cli.daemon.routes.handleStatusOne
import com.qkt.cli.daemon.routes.handleStop
import com.qkt.cli.daemon.routes.parseQuery
import com.qkt.cli.daemon.routes.promotionStore
import com.qkt.cli.daemon.routes.respond
import com.qkt.cli.daemon.routes.routeJson
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.qkt.dsl.portfolio.PortfolioLoader
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ControlRoutes {
    fun dispatch(
        registry: StrategyRegistry,
        startedAt: Instant,
        stateDir: StateDir?,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer? = null,
        shutdown: () -> Unit,
        notifierMetrics: com.qkt.notify.NotifierMetrics? = null,
        prometheusMetricsEnabled: Boolean = true,
        promotionGates: PromotionGateConfig = PromotionGateConfig.DISABLED,
        controlToken: String? = null,
        pendingAutoDeploys: () -> List<AutoDeployRetrier.Pending> = { emptyList() },
    ): HttpHandler =
        HttpHandler { ex ->
            val path = ex.requestURI.path
            val method = ex.requestMethod
            try {
                if (method == "POST" && controlToken != null && !isAuthorized(ex, controlToken)) {
                    ex.responseHeaders.add("WWW-Authenticate", "Bearer")
                    respond(ex, 401, """{"error":"unauthorized"}""")
                    return@HttpHandler
                }
                when {
                    method == "GET" && path == "/health" -> handleHealth(ex, registry, startedAt, pendingAutoDeploys())
                    method == "POST" && path == "/deploy" ->
                        handleDeploy(ex, registry, stateDir, portfolioDeployer, promotionGates)
                    method == "POST" && path == "/resync" ->
                        handleResync(ex, registry, stateDir, portfolioDeployer, promotionGates)
                    method == "GET" && path == "/list" -> handleList(ex, registry, stateDir, promotionGates)
                    method == "POST" && path.startsWith("/stop/") -> handleStop(ex, registry, stateDir, path)
                    method == "POST" && path.startsWith("/start/") -> handleStart(ex, registry, stateDir, path)
                    method == "POST" && path == "/halt" -> handleHalt(ex, registry, stateDir, null)
                    method == "POST" && path.startsWith("/halt/") ->
                        handleHalt(ex, registry, stateDir, path.removePrefix("/halt/").trim('/').ifBlank { null })
                    method == "POST" && path == "/kill" -> handleKill(ex, registry, stateDir, null)
                    method == "POST" && path.startsWith("/kill/") ->
                        handleKill(ex, registry, stateDir, path.removePrefix("/kill/").trim('/').ifBlank { null })
                    method == "POST" && path == "/resume" -> handleResume(ex, registry, stateDir, null)
                    method == "POST" && path.startsWith("/resume/") ->
                        handleResume(ex, registry, stateDir, path.removePrefix("/resume/").trim('/').ifBlank { null })
                    method == "POST" && path == "/shutdown" -> handleShutdown(ex, stateDir, shutdown)
                    method == "GET" && path.startsWith("/logs/") ->
                        handleLogs(ex, registry, stateDir, path)
                    method == "GET" && path == "/status" -> handleStatusAll(ex, registry)
                    method == "GET" && path.startsWith("/status/") -> handleStatusOne(ex, registry, path)
                    method == "GET" && path.startsWith("/reconcile/") ->
                        handleReconcile(ex, registry, path.removePrefix("/reconcile/").trim('/'))
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

    private fun isAuthorized(
        ex: HttpExchange,
        expectedToken: String,
    ): Boolean {
        val header = ex.requestHeaders.getFirst("Authorization") ?: return false
        val prefix = "Bearer "
        if (!header.regionMatches(0, prefix, 0, prefix.length, ignoreCase = true)) return false
        val supplied = header.substring(prefix.length)
        return MessageDigest.isEqual(
            supplied.toByteArray(StandardCharsets.UTF_8),
            expectedToken.toByteArray(StandardCharsets.UTF_8),
        )
    }

    private fun handleDeploy(
        ex: HttpExchange,
        registry: StrategyRegistry,
        stateDir: StateDir?,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer?,
        promotionGates: PromotionGateConfig,
    ) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val obj =
            try {
                routeJson.parseToJsonElement(body) as? JsonObject
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
        val promotionResult =
            evaluateDeployPromotion(
                ex = ex,
                name = name,
                path = path,
                stateDir = stateDir,
                gates = promotionGates,
                params = params,
            ) ?: return
        if (promotionResult.blocked) {
            return respond(
                ex,
                409,
                """{"error":"production deploy blocked","kind":"promotion-gate",""" +
                    """"promotion":${PromotionJson.encode(promotionResult)}}""",
            )
        }

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
                        """"state":"running","startedAt":"${handle.startedAt}",""" +
                        """"promotion":${PromotionJson.encode(promotionResult)}}""",
                )
                OperatorJournal
                    .from(stateDir, "http")
                    ?.record(
                        "deploy",
                        target = name,
                        affected = listOf(handle.name),
                        details =
                            mapOf(
                                "kind" to "strategy",
                                "promotionState" to promotionResult.state,
                                "promotionEligible" to promotionResult.eligibleForProduction.toString(),
                                "promotionWaived" to promotionResult.waivedGates.joinToString(","),
                            ),
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
                        val record = portfolioDeployer.deploy(name, compiled, ignoreMismatches)
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
                        """"startedAt":"${record.startedAt}","children":$childrenJson,""" +
                        """"promotion":${PromotionJson.encode(promotionResult)}}""",
                )
                OperatorJournal
                    .from(stateDir, "http")
                    ?.record(
                        action = "deploy",
                        target = name,
                        affected = listOf(record.name) + record.children.map { it.name },
                        details =
                            mapOf(
                                "kind" to "portfolio",
                                "promotionState" to promotionResult.state,
                                "promotionEligible" to promotionResult.eligibleForProduction.toString(),
                                "promotionWaived" to promotionResult.waivedGates.joinToString(","),
                            ),
                    )
            }
        }
    }

    private fun handleResync(
        ex: HttpExchange,
        registry: StrategyRegistry,
        stateDir: StateDir?,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer?,
        promotionGates: PromotionGateConfig,
    ) {
        val body = ex.requestBody.readBytes().toString(Charsets.UTF_8)
        val obj =
            try {
                routeJson.parseToJsonElement(body) as? JsonObject
                    ?: return respond(ex, 400, """{"error":"body must be a JSON object"}""")
            } catch (_: Exception) {
                return respond(ex, 400, """{"error":"invalid JSON body"}""")
            }
        val file = obj["file"]?.jsonPrimitive?.contentOrNull
        val name = obj["name"]?.jsonPrimitive?.contentOrNull
        val dryRun = obj["dryRun"]?.jsonPrimitive?.booleanOrNull ?: false
        if (file.isNullOrBlank() || name.isNullOrBlank()) {
            return respond(ex, 400, """{"error":"missing 'file' or 'name'}""")
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
        val promotionResult =
            evaluateDeployPromotion(
                ex = ex,
                name = name,
                path = path,
                stateDir = stateDir,
                gates = promotionGates,
                params = params,
            ) ?: return
        if (promotionResult.blocked) {
            return respond(
                ex,
                409,
                """{"error":"production resync blocked","kind":"promotion-gate",""" +
                    """"promotion":${PromotionJson.encode(promotionResult)}}""",
            )
        }

        val parsed =
            when (val r = Dsl.parseFileAny(path)) {
                is ParseResult.Success -> r.value
                is ParseResult.Failure -> {
                    val msg = r.errors.joinToString(";") { it.message }.replace("\"", "'")
                    return respond(ex, 400, """{"error":"parse failed: $msg"}""")
                }
            }

        when (parsed) {
            is ParsedFile.StrategyFile ->
                handleStrategyResync(
                    ex = ex,
                    registry = registry,
                    stateDir = stateDir,
                    name = name,
                    path = path,
                    dryRun = dryRun,
                    ignoreMismatches = ignoreMismatches,
                    promotionResult = promotionResult,
                )
            is ParsedFile.PortfolioFile ->
                handlePortfolioResync(
                    ex = ex,
                    registry = registry,
                    stateDir = stateDir,
                    portfolioDeployer = portfolioDeployer,
                    name = name,
                    path = path,
                    dryRun = dryRun,
                    ignoreMismatches = ignoreMismatches,
                    promotionResult = promotionResult,
                )
        }
    }

    private fun handleStrategyResync(
        ex: HttpExchange,
        registry: StrategyRegistry,
        stateDir: StateDir?,
        name: String,
        path: Path,
        dryRun: Boolean,
        ignoreMismatches: Boolean,
        promotionResult: PromotionGateResult,
    ) {
        val existing =
            registry.get(name)
                ?: return respond(ex, 404, """{"error":"unknown strategy: $name"}""")
        if (existing.childMeta != null) {
            return respond(ex, 409, """{"error":"strategy '$name' is a portfolio child; resync the parent"}""")
        }
        if (dryRun) {
            return respond(
                ex,
                200,
                """{"name":"$name","kind":"strategy","state":"planned","dryRun":true,""" +
                    """"affected":["$name"],"promotion":${PromotionJson.encode(promotionResult)}}""",
            )
        }
        val handle =
            try {
                registry.resyncStrategy(name, path, ignoreMismatches)
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
        OperatorJournal
            .from(stateDir, "http")
            ?.record(
                "resync",
                target = name,
                affected = listOf(handle.name),
                details =
                    mapOf(
                        "kind" to "strategy",
                        "file" to path.toAbsolutePath().normalize().toString(),
                        "promotionState" to promotionResult.state,
                        "promotionEligible" to promotionResult.eligibleForProduction.toString(),
                    ),
            )
        respond(
            ex,
            200,
            """{"name":"${handle.name}","kind":"strategy","port":${handle.port},""" +
                """"state":"running","dryRun":false,"startedAt":"${handle.startedAt}",""" +
                """"affected":["${handle.name}"],"promotion":${PromotionJson.encode(promotionResult)}}""",
        )
    }

    private fun handlePortfolioResync(
        ex: HttpExchange,
        registry: StrategyRegistry,
        stateDir: StateDir?,
        portfolioDeployer: com.qkt.cli.daemon.portfolio.PortfolioDeployer?,
        name: String,
        path: Path,
        dryRun: Boolean,
        ignoreMismatches: Boolean,
        promotionResult: PromotionGateResult,
    ) {
        if (registry.get(name) != null) {
            return respond(ex, 409, """{"error":"strategy '$name' is not a portfolio"}""")
        }
        registry.getPortfolio(name)
            ?: return respond(ex, 404, """{"error":"unknown portfolio: $name"}""")
        if (portfolioDeployer == null) {
            return respond(ex, 501, """{"error":"portfolio resync not configured on this daemon"}""")
        }
        if (dryRun) {
            return respond(
                ex,
                200,
                """{"name":"$name","kind":"portfolio","state":"planned","dryRun":true,""" +
                    """"affected":["$name"],"promotion":${PromotionJson.encode(promotionResult)}}""",
            )
        }
        val record =
            try {
                val compiled = PortfolioLoader.load(path)
                registry.retirePortfolioForResync(name, compiled.children.map { it.alias })
                val replacement = portfolioDeployer.deploy(name, compiled, ignoreMismatches)
                registry.registerPortfolio(replacement)
                replacement
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
        val affected = listOf(record.name) + record.children.map { it.name }
        OperatorJournal
            .from(stateDir, "http")
            ?.record(
                action = "resync",
                target = name,
                affected = affected,
                details =
                    mapOf(
                        "kind" to "portfolio",
                        "file" to path.toAbsolutePath().normalize().toString(),
                        "promotionState" to promotionResult.state,
                        "promotionEligible" to promotionResult.eligibleForProduction.toString(),
                    ),
            )
        val affectedJson = affected.joinToString(",", "[", "]") { """"$it"""" }
        respond(
            ex,
            200,
            """{"name":"${record.name}","kind":"portfolio","state":"running",""" +
                """"dryRun":false,"startedAt":"${record.startedAt}","affected":$affectedJson,""" +
                """"promotion":${PromotionJson.encode(promotionResult)}}""",
        )
    }

    private fun evaluateDeployPromotion(
        ex: HttpExchange,
        name: String,
        path: Path,
        stateDir: StateDir?,
        gates: PromotionGateConfig,
        params: Map<String, String>,
    ): PromotionGateResult? {
        val store = promotionStore(stateDir, gates)
        val now = Instant.now()
        val waiver = deployWaiver(params, now)
        if (params.containsKey("waive") && waiver == null) {
            respond(ex, 400, """{"error":"--waive requires a non-empty reason"}""")
            return null
        }
        if (waiver != null) {
            val strategyHash = PromotionGateEvaluator.strategyHash(path)
            val existing = store.latest(name, strategyHash)
            val record =
                existing
                    ?.update(now = now, waivers = existing.waivers + waiver)
                    ?: PromotionRecord.create(
                        strategy = name,
                        strategyHash = strategyHash,
                        state = PromotionState.DRAFT,
                        rationale = "deploy waiver without prior promotion record",
                        now = now,
                        waivers = listOf(waiver),
                    )
            store.append(record)
            OperatorJournal
                .from(stateDir, "http")
                ?.record(
                    action = "promotion.waive",
                    target = name,
                    affected = listOf(name),
                    details =
                        mapOf(
                            "gates" to waiver.gates.joinToString(","),
                            "reason" to waiver.reason,
                            "strategyHash" to strategyHash,
                            "expiresAt" to waiver.expiresAt,
                        ),
                )
        }
        return PromotionGateEvaluator(gates)
            .evaluate(
                strategy = name,
                strategyPath = path,
                store = store,
                now = now,
            )
    }

    private fun deployWaiver(
        params: Map<String, String>,
        now: Instant,
    ): PromotionWaiver? {
        val raw = params["waive"] ?: return null
        val reason = params["reason"]?.takeIf { it.isNotBlank() } ?: return null
        val gates =
            raw
                .split(',')
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .ifEmpty { listOf("all") }
        val expiresAt = params["expires"]?.takeIf { it.isNotBlank() }
        if (expiresAt != null && runCatching { Instant.parse(expiresAt) }.isFailure) return null
        return PromotionWaiver(
            gates = gates,
            reason = reason,
            actor = params["actor"]?.takeIf { it.isNotBlank() } ?: "deploy",
            createdAt = now.toString(),
            expiresAt = expiresAt,
        )
    }
}
