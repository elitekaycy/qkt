package com.qkt.app.order

import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import com.qkt.persistence.PersistedTrailingStop
import java.math.BigDecimal

/**
 * Moving state of engine-held protective stops, keyed by order id: the favorable extreme
 * (high-water mark) a trailing stop follows, the one-way arm flag of an armed trail, the step a
 * stepped stop has reached, the intervals a time-tightening stop has counted, and the stop level
 * those produce. [ManagedStopTicker] advances it per tick; this class owns it and answers
 * "where is this stop now" via [trailLevel].
 */
internal class ManagedStopBook {
    private val trailingHwm: MutableMap<String, BigDecimal> = mutableMapOf()

    /**
     * One-way arming state for [OrderRequest.ArmedTrailingStop] orders. `false` while the stop
     * sits at `entry ± distance`; flips to `true` once MFE crosses the threshold and the stop
     * starts trailing the high-water mark. Never reverts. See #48.
     */
    private val armedTrailArmed: MutableMap<String, Boolean> = mutableMapOf()
    private val steppedStopIndex: MutableMap<String, Int> = mutableMapOf()
    private val timeTightenIntervals: MutableMap<String, Long> = mutableMapOf()
    private val managedStopLevel: MutableMap<String, BigDecimal> = mutableMapOf()

    /** True when a high-water mark or step moved since the last snapshot was written. */
    var dirty: Boolean = false

    fun hwm(id: String): BigDecimal? = trailingHwm[id]

    fun setHwm(
        id: String,
        hwm: BigDecimal,
    ) {
        trailingHwm[id] = hwm
    }

    fun armed(id: String): Boolean? = armedTrailArmed[id]

    fun arm(id: String) {
        armedTrailArmed[id] = true
    }

    fun stepIndex(id: String): Int? = steppedStopIndex[id]

    fun setStepIndex(
        id: String,
        index: Int,
    ) {
        steppedStopIndex[id] = index
    }

    fun elapsedIntervals(id: String): Long? = timeTightenIntervals[id]

    fun setElapsedIntervals(
        id: String,
        intervals: Long,
    ) {
        timeTightenIntervals[id] = intervals
    }

    fun level(id: String): BigDecimal? = managedStopLevel[id]

    fun setLevel(
        id: String,
        level: BigDecimal,
    ) {
        managedStopLevel[id] = level
    }

    /**
     * Starts tracking [request] as it begins resting engine-side. A plain trailing stop starts
     * its mark at [trailingSeed] (the last price seen), a managed stop at its entry price.
     */
    fun startTracking(
        request: OrderRequest,
        trailingSeed: BigDecimal?,
    ) {
        if (request is OrderRequest.TrailingStop || request is OrderRequest.TrailingStopLimit) {
            if (trailingSeed != null) trailingHwm[request.id] = trailingSeed
        }
        if (request is OrderRequest.ArmedTrailingStop) {
            // Seed hwm at the entry price — MFE = |hwm - entry| starts at 0. Each tick moves hwm
            // toward the favorable side. Pre-arm the stop sits at entry ± distance regardless of
            // hwm; once armed, hwm leads.
            trailingHwm[request.id] = request.entryPrice
            armedTrailArmed[request.id] = false
        }
        if (request is OrderRequest.SteppedStop) {
            trailingHwm[request.id] = request.entryPrice
            steppedStopIndex[request.id] = 0
            managedStopLevel[request.id] =
                initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        }
        if (request is OrderRequest.TimeTighteningStop) {
            timeTightenIntervals[request.id] = 0L
            managedStopLevel[request.id] =
                initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        }
    }

    /** Resumes [request] after a restart from its persisted [dynamicState], or from its anchor. */
    fun restore(
        clientOrderId: String,
        request: OrderRequest,
        dynamicState: PersistedTrailingStop?,
    ) {
        dynamicState?.let { trailingHwm[clientOrderId] = it.hwm }
        when (request) {
            is OrderRequest.TrailingStop, is OrderRequest.TrailingStopLimit -> Unit
            is OrderRequest.ArmedTrailingStop ->
                armedTrailArmed[clientOrderId] = dynamicState?.armed ?: false
            is OrderRequest.SteppedStop -> {
                steppedStopIndex[clientOrderId] = dynamicState?.stepIndex ?: 0
                managedStopLevel[clientOrderId] =
                    dynamicState?.stopLevel
                        ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
            }
            is OrderRequest.TimeTighteningStop -> {
                timeTightenIntervals[clientOrderId] = dynamicState?.elapsedIntervals ?: 0L
                managedStopLevel[clientOrderId] =
                    dynamicState?.stopLevel
                        ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
            }
            is OrderRequest.StopLimit -> Unit
            else -> error("unsupported restored engine-held order ${request::class.simpleName}")
        }
        when (request) {
            is OrderRequest.ArmedTrailingStop -> trailingHwm.putIfAbsent(clientOrderId, request.entryPrice)
            is OrderRequest.SteppedStop -> trailingHwm.putIfAbsent(clientOrderId, request.entryPrice)
            else -> Unit
        }
    }

    /** Drops all moving state for [id]; its order was reclaimed. */
    fun forget(id: String) {
        trailingHwm.remove(id)
        armedTrailArmed.remove(id)
        steppedStopIndex.remove(id)
        timeTightenIntervals.remove(id)
        managedStopLevel.remove(id)
    }

    /** Current stop level of [managed], or null while it has no reference price yet. */
    fun trailLevel(managed: ManagedOrder): BigDecimal? =
        when (val request = managed.request) {
            is OrderRequest.ArmedTrailingStop -> {
                val reference = if (armedTrailArmed[managed.id] == true) trailingHwm[managed.id] else request.entryPrice
                // Exit-side SELL closes a long → stop sits BELOW the reference (`hwm` or
                // `entry`) and fires on a drop. Exit-side BUY closes a short → stop ABOVE.
                reference?.let {
                    when (request.side) {
                        Side.SELL -> it - request.trailDistance
                        Side.BUY -> it + request.trailDistance
                    }
                }
            }
            is OrderRequest.SteppedStop, is OrderRequest.TimeTighteningStop -> managedStopLevel[managed.id]
            is OrderRequest.TrailingStop ->
                trailingHwm[managed.id]?.let { trailingLevel(request.side, it, request.trailAmount, request.trailMode) }
            is OrderRequest.TrailingStopLimit ->
                trailingHwm[managed.id]?.let { trailingLevel(request.side, it, request.trailAmount, request.trailMode) }
            else -> null
        }
}
