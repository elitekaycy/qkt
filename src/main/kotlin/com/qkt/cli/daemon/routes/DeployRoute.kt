package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateConfig
import com.qkt.cli.PromotionJson
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * `POST /deploy` — parses the `.qkt` file named in the body and deploys it as a
 * strategy or portfolio, after the promotion gate allows it.
 */
internal fun handleDeploy(
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
