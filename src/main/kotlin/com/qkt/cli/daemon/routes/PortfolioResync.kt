package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateResult
import com.qkt.cli.PromotionJson
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.qkt.dsl.portfolio.PortfolioLoader
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Path

/**
 * Resyncs a portfolio by retiring its children and deploying the new version under
 * the same name.
 */
internal fun handlePortfolioResync(
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
