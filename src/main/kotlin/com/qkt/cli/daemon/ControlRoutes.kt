package com.qkt.cli.daemon

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.daemon.routes.handleDeploy
import com.qkt.cli.daemon.routes.handleHalt
import com.qkt.cli.daemon.routes.handleHealth
import com.qkt.cli.daemon.routes.handleKill
import com.qkt.cli.daemon.routes.handleLatencyAll
import com.qkt.cli.daemon.routes.handleList
import com.qkt.cli.daemon.routes.handleLogs
import com.qkt.cli.daemon.routes.handleMetrics
import com.qkt.cli.daemon.routes.handleReconcile
import com.qkt.cli.daemon.routes.handleResume
import com.qkt.cli.daemon.routes.handleResync
import com.qkt.cli.daemon.routes.handleShutdown
import com.qkt.cli.daemon.routes.handleStart
import com.qkt.cli.daemon.routes.handleStatusAll
import com.qkt.cli.daemon.routes.handleStatusOne
import com.qkt.cli.daemon.routes.handleStop
import com.qkt.cli.daemon.routes.respond
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant

/**
 * The daemon's HTTP control API: authenticates POSTs against the optional bearer token and
 * routes each request to its handler in [com.qkt.cli.daemon.routes]. Every handler answers
 * JSON (plain text for `/logs` and `/metrics`); an unmatched route is a 404 and an escaped
 * exception a 500.
 */
object ControlRoutes {
    /**
     * Builds the control-plane [HttpHandler]. `/metrics` is routed only when
     * [prometheusMetricsEnabled]; POSTs require `Authorization: Bearer` when [controlToken] is set.
     */
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
}
