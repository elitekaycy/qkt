package com.qkt.cli.daemon.routes

import com.qkt.cli.PromotionGateResult
import com.qkt.cli.PromotionJson
import com.qkt.cli.daemon.OperatorJournal
import com.qkt.cli.daemon.StateDir
import com.qkt.cli.daemon.StrategyRegistry
import com.sun.net.httpserver.HttpExchange
import java.nio.file.Path

/**
 * Resyncs a standalone strategy in place; portfolio children must be resynced
 * through their parent.
 */
internal fun handleStrategyResync(
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
