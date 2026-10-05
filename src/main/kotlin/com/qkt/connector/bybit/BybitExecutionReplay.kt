package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.connector.bybit.spot.BybitSpotStateRecovery
import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * Replays one [category]'s executions since shortly before the last fill from `/v5/execution/list`: each
 * unseen `Trade` of an order [strategyOf] knows goes to [onFill] (by default published as [BybitFills] maps
 * it); one of an order qkt did not place is logged once and never booked. Each `Funding` goes to
 * [onFunding], and any other [BybitExecutionKind] (a liquidation, an auto-deleverage, a delivery) is left to
 * the position reconcile, since no order of ours filled.
 */
class BybitExecutionReplay(
    private val category: String,
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
    private val strategyOf: (String) -> String?,
    private val lastFillTimeProvider: () -> Long,
    private val seenExecIds: MutableSet<String>,
    private val onFunding: (JsonObject) -> Unit = {},
    private val onFill: ((BybitOrderTranslator.ParsedExecution, String) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(BybitExecutionReplay::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val unowned = boundedExecIdSet(1_000)

    /**
     * Replays what was missed, oldest first (Bybit lists newest first, so an order's last execution would
     * otherwise come before its earlier ones); returns the client order ids that filled in the window.
     */
    fun reconcile(): Set<String> {
        val startTime = (lastFillTimeProvider() - 60_000L).coerceAtLeast(0L)
        var cursor = ""
        val unseen = mutableListOf<JsonObject>()
        val cap = BybitSpotStateRecovery.MAX_EXECUTIONS_PER_RECONCILE
        while (unseen.size < cap) {
            val params =
                buildMap {
                    put("category", category)
                    put("startTime", startTime.toString())
                    put("limit", "50")
                    if (cursor.isNotEmpty()) put("cursor", cursor)
                }
            val page = read(params, "execution reconcile")
            val fresh = page.first.filter { it["execId"]?.jsonPrimitive?.content !in seenExecIds }
            unseen += fresh
            cursor = page.second
            // Stop if a non-empty page held no new executions: a perpetual cursor over already-seen
            // execs would otherwise spin.
            if (cursor.isEmpty() || page.first.isEmpty() || fresh.isEmpty()) break
        }
        val executedOrderIds = mutableSetOf<String>()
        oldestFirst(unseen).take(cap).forEach { route(it, executedOrderIds) }
        return executedOrderIds
    }

    /**
     * Replays the executions of order [clientOrderId] alone, oldest first (Bybit's default window: 7 days).
     * The oldest that add up to [alreadyBooked] were booked before a restart: marked seen, not published.
     */
    fun replayOrder(
        clientOrderId: String,
        alreadyBooked: BigDecimal = BigDecimal.ZERO,
    ) {
        val params = mapOf("category" to category, "orderLinkId" to clientOrderId, "limit" to "100")
        var skipped = BigDecimal.ZERO
        for (execution in oldestFirst(read(params, "order execution replay").first)) {
            val quantity = execution["execQty"]?.jsonPrimitive?.content?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val execId = execution["execId"]?.jsonPrimitive?.content
            if (execId != null &&
                BybitExecutionKind.of(execution) == BybitExecutionKind.FILL &&
                skipped + quantity <= alreadyBooked
            ) {
                skipped += quantity
                seenExecIds.add(execId)
                continue
            }
            route(execution, mutableSetOf())
        }
    }

    private fun read(
        params: Map<String, String>,
        what: String,
    ): Pair<List<JsonObject>, String> {
        val result = requireBybitOk(transport.getSigned("/v5/execution/list", params), what, json)["result"]?.jsonObject
        val list = result?.get("list")?.jsonArray ?: throw IllegalStateException("$what response omitted result.list")
        return list.map { it.jsonObject } to (result["nextPageCursor"]?.jsonPrimitive?.content ?: "")
    }

    private fun oldestFirst(executions: List<JsonObject>): List<JsonObject> =
        executions.sortedWith(
            compareBy(
                { it["execTime"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L },
                { it["seq"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L },
            ),
        )

    /** Handles one [execution]; true when it had not been seen. */
    private fun route(
        execution: JsonObject,
        executedOrderIds: MutableSet<String>,
    ): Boolean {
        val execId = execution["execId"]?.jsonPrimitive?.content ?: error("missing execId: $execution")
        when (BybitExecutionKind.of(execution)) {
            BybitExecutionKind.FUNDING -> {
                if (execId in seenExecIds) return false
                onFunding(execution)
                return true
            }
            BybitExecutionKind.OTHER -> {
                if (!seenExecIds.add(execId)) return false
                log.info("Bybit {} execution is not a fill; the position reconcile applies it: {}", category, execution)
                return true
            }
            BybitExecutionKind.FILL -> Unit
        }
        val exec = BybitOrderTranslator.parseExecution(execution)
        executedOrderIds.add(exec.clientOrderId)
        val strategyId = strategyOf(exec.clientOrderId)
        if (strategyId == null) {
            // Not marked seen: a restart that restores its order still books it.
            if (unowned.add(execId)) {
                log.warn("Bybit {} execution of an order qkt did not place; not booked: {}", category, execution)
            }
            return false
        }
        if (!seenExecIds.add(exec.execId)) return false
        onFill?.invoke(exec, strategyId) ?: bus.publish(BybitFills.event(category, exec, strategyId, clock.now()))
        return true
    }
}
