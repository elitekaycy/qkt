package com.qkt.connector.bybit

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

/**
 * One Bybit broker's view of the private `execution` topic: each `Trade` execution of its [category] is
 * attributed through [strategyOf] and handed once (by `execId`, in [seenExecIds]) to [onFill], which by
 * default publishes it as [BybitFills] maps it. An execution of an order [strategyOf] does not know (another
 * client's on the same account) is logged once and never booked, nor marked seen, so a restart that
 * restores its order can still book it. The all-in-one topic carries every category of the login, so an
 * entry naming another category is the sibling broker's and is skipped. A `Funding` execution goes to
 * [onFunding]; any other [BybitExecutionKind] is not a fill (see there) and is only logged.
 */
class BybitExecutionStream(
    private val category: String,
    private val bus: EventBus,
    private val clock: Clock,
    private val seenExecIds: MutableSet<String>,
    private val lastFillTime: AtomicLong,
    private val strategyOf: (String) -> String?,
    private val onFunding: (JsonObject) -> Unit = {},
    private val onFill: (BybitOrderTranslator.ParsedExecution, String) -> Unit = { exec, strategyId ->
        bus.publish(BybitFills.event(category, exec, strategyId, clock.now()))
    },
) {
    private val log = LoggerFactory.getLogger(BybitExecutionStream::class.java)
    private val unowned = boundedExecIdSet(1_000)

    /** Handles one `execution` [frame]. */
    fun onFrame(frame: JsonObject) {
        val list = frame["data"]?.jsonArray ?: return
        for (entry in list) {
            val execution = entry.jsonObject
            val entryCategory = execution["category"]?.jsonPrimitive?.content
            if (entryCategory != null && entryCategory != category) continue
            when (BybitExecutionKind.of(execution)) {
                BybitExecutionKind.FILL -> fill(execution)
                BybitExecutionKind.FUNDING -> onFunding(execution)
                BybitExecutionKind.OTHER ->
                    log.info(
                        "Bybit {} execution is not a fill; the position reconcile applies it: {}",
                        category,
                        execution,
                    )
            }
        }
    }

    private fun fill(execution: JsonObject) {
        val exec = BybitOrderTranslator.parseExecution(execution)
        val strategyId = strategyOf(exec.clientOrderId)
        if (strategyId == null) {
            if (unowned.add(exec.execId)) {
                log.warn("Bybit {} execution of an order qkt did not place; not booked: {}", category, execution)
            }
            return
        }
        if (!seenExecIds.add(exec.execId)) return
        onFill(exec, strategyId)
        lastFillTime.set(clock.now())
    }
}
