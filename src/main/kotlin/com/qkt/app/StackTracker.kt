package com.qkt.app

import com.qkt.dsl.ast.BracketAst
import com.qkt.execution.StackPlan
import java.math.BigDecimal

internal class StackTracker {
    private val active: MutableMap<String, ActiveStack> = mutableMapOf()

    fun register(
        stackId: String,
        plan: StackPlan,
        outerBracket: BracketAst?,
    ) {
        active[stackId] =
            ActiveStack(
                id = stackId,
                plan = plan,
                outerBracket = outerBracket,
                withinMillis = plan.withinMillis,
            )
    }

    fun setAnchor(
        stackId: String,
        anchor: BigDecimal,
        firstFillEpochMs: Long,
    ) {
        val s = active[stackId] ?: return
        active[stackId] =
            s.copy(
                anchor = anchor,
                deadlineEpochMs = s.withinMillis?.let { firstFillEpochMs + it },
            )
    }

    fun get(stackId: String): ActiveStack? = active[stackId]

    fun all(): Collection<ActiveStack> = active.values.toList()

    fun addPending(
        stackId: String,
        layerOrderId: String,
    ) {
        active[stackId]?.pendingLayerIds?.add(layerOrderId)
    }

    fun markFilled(layerOrderId: String): String? {
        val entry = active.entries.firstOrNull { layerOrderId in it.value.pendingLayerIds }
        if (entry != null) {
            entry.value.pendingLayerIds.remove(layerOrderId)
            entry.value.filledLayerIds.add(layerOrderId)
            return entry.key
        }
        // Layer 1 fills do not pass through pending — caller registers them separately.
        val layerOneOwner =
            active.entries.firstOrNull { it.value.layerOneOrderId == layerOrderId }
        if (layerOneOwner != null) {
            layerOneOwner.value.filledLayerIds.add(layerOrderId)
            return layerOneOwner.key
        }
        return null
    }

    fun setLayerOneOrderId(
        stackId: String,
        orderId: String,
    ) {
        active[stackId]?.let { active[stackId] = it.copy(layerOneOrderId = orderId) }
    }

    fun terminate(stackId: String): ActiveStack? = active.remove(stackId)

    fun stackOwning(orderId: String): String? =
        active.entries
            .firstOrNull {
                orderId in it.value.pendingLayerIds ||
                    orderId in it.value.filledLayerIds ||
                    orderId == it.value.layerOneOrderId
            }?.key

    internal data class ActiveStack(
        val id: String,
        val plan: StackPlan,
        val outerBracket: BracketAst?,
        val withinMillis: Long?,
        val anchor: BigDecimal? = null,
        val deadlineEpochMs: Long? = null,
        val layerOneOrderId: String? = null,
        val pendingLayerIds: MutableSet<String> = mutableSetOf(),
        val filledLayerIds: MutableSet<String> = mutableSetOf(),
    )
}
