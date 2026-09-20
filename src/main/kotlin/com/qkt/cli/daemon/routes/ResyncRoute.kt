package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionJson
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.dsl.parse.Dsl
import com.qkt.dsl.parse.ParseResult
import com.qkt.dsl.parse.ParsedFile
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /resync` — replaces a running strategy or portfolio with a new version of its
 * file (or plans it with `dryRun`), after the promotion gate allows it.
 */
internal fun handleResync(
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
