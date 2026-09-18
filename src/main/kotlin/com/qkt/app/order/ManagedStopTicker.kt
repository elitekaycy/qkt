package com.qkt.app.order

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.execution.ManagedOrder
import com.qkt.execution.OrderRequest
import java.math.BigDecimal
import org.slf4j.Logger

/**
 * Per-tick policy of each engine-held stop kind: a trailing mark follows the favorable extreme,
 * an armed trail arms once MFE crosses its threshold, a stepped stop ratchets through its steps,
 * a time-tightening stop closes in each elapsed interval. Levels only ever tighten.
 *
 * Hot path: [onTick] runs once per pending engine-held order on each tick of its symbol and does
 * O(1) map work (a stepped stop may advance several steps at once). A transition that must
 * survive a crash (arming, a step, a tightening) calls [persist] at once; high-water-mark drift
 * only marks the book dirty for the heartbeat flush. A tightened level is also pushed to the
 * venue via [tightenAtVenue] so the offline backstop follows the engine.
 */
internal class ManagedStopTicker(
    private val stops: ManagedStopBook,
    private val clock: Clock,
    private val persist: () -> Unit,
    private val tightenAtVenue: (managed: ManagedOrder, stopLoss: BigDecimal, transition: String) -> Unit,
    private val log: Logger,
) {
    /** Advances [managed]'s mark, arm flag, step or tightening for a tick at [tickPrice]. */
    fun onTick(
        managed: ManagedOrder,
        tickPrice: BigDecimal,
    ) {
        when (val request = managed.request) {
            is OrderRequest.ArmedTrailingStop -> advanceArmedTrail(managed.id, request, tickPrice)
            is OrderRequest.SteppedStop -> advanceSteppedStop(managed, request, tickPrice)
            is OrderRequest.TimeTighteningStop -> advanceTimeTightening(managed, request)
            is OrderRequest.TrailingStop -> advanceTrailingMark(managed.id, request.side, tickPrice)
            is OrderRequest.TrailingStopLimit -> advanceTrailingMark(managed.id, request.side, tickPrice)
            else -> Unit
        }
    }

    private fun advanceTrailingMark(
        id: String,
        side: Side,
        tickPrice: BigDecimal,
    ) {
        val current = stops.hwm(id)
        val newHwm =
            when (side) {
                Side.SELL -> if (current == null || tickPrice > current) tickPrice else current
                Side.BUY -> if (current == null || tickPrice < current) tickPrice else current
            }
        if (newHwm != current) {
            stops.setHwm(id, newHwm)
            stops.dirty = true
        }
    }

    private fun advanceArmedTrail(
        id: String,
        request: OrderRequest.ArmedTrailingStop,
        tickPrice: BigDecimal,
    ) {
        // The favorable side for an exit is the direction its entry profits in: exit SELL (long
        // entry) tracks the high, exit BUY (short entry) tracks the low.
        val current = stops.hwm(id) ?: request.entryPrice
        val newHwm =
            when (request.side) {
                Side.SELL -> if (tickPrice > current) tickPrice else current
                Side.BUY -> if (tickPrice < current) tickPrice else current
            }
        if (newHwm != current) {
            stops.setHwm(id, newHwm)
            stops.dirty = true
        }
        // Arming gate: MFE = |hwm - entry|. Once MFE crosses the threshold, arm for life.
        if (stops.armed(id) == false) {
            val mfe = newHwm.subtract(request.entryPrice).abs()
            if (mfe.compareTo(request.mfeThreshold) >= 0) {
                stops.arm(id)
                log.info(
                    "armed-trail armed: order_id={} symbol={} entry={} hwm={} mfe={} threshold={}",
                    id,
                    request.symbol,
                    request.entryPrice,
                    newHwm,
                    mfe,
                    request.mfeThreshold,
                )
                // Persist the one-time arm transition immediately so a crash right after arming
                // still resumes armed on restart, not reset to the entry (#436).
                persist()
            }
        }
    }

    private fun advanceSteppedStop(
        managed: ManagedOrder,
        request: OrderRequest.SteppedStop,
        tickPrice: BigDecimal,
    ) {
        val id = managed.id
        val currentHwm = stops.hwm(id) ?: request.entryPrice
        val newHwm =
            when (request.side) {
                Side.SELL -> if (tickPrice > currentHwm) tickPrice else currentHwm
                Side.BUY -> if (tickPrice < currentHwm) tickPrice else currentHwm
            }
        if (newHwm != currentHwm) {
            stops.setHwm(id, newHwm)
            stops.dirty = true
        }
        val mfe = newHwm.subtract(request.entryPrice).abs()
        var index = stops.stepIndex(id) ?: 0
        var level = stops.level(id) ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        var advanced = false
        var tightened = false
        while (index < request.steps.size && mfe >= request.steps[index].mfeThreshold) {
            val candidate = profitStopLevel(request.side, request.entryPrice, request.steps[index].profitDistance)
            if (isTighter(request.side, candidate, level)) {
                level = candidate
                stops.setLevel(id, candidate)
                tightened = true
                log.info(
                    "stepped stop advanced: order_id={} symbol={} step={} mfe={} stop={}",
                    id,
                    request.symbol,
                    index + 1,
                    mfe,
                    candidate,
                )
            } else {
                log.warn(
                    "stepped stop skipped widening target: order_id={} symbol={} step={} current={} candidate={}",
                    id,
                    request.symbol,
                    index + 1,
                    level,
                    candidate,
                )
            }
            index++
            advanced = true
        }
        if (advanced) {
            stops.setStepIndex(id, index)
            stops.dirty = true
            persist()
            if (tightened) tightenAtVenue(managed, level, "step-$index")
        }
    }

    private fun advanceTimeTightening(
        managed: ManagedOrder,
        request: OrderRequest.TimeTighteningStop,
    ) {
        val id = managed.id
        val floorLevel = initialStopLevel(request.side, request.entryPrice, request.floorDistance)
        if (stops.level(id)?.compareTo(floorLevel) == 0) return
        val elapsedMs = (clock.now() - request.timestamp).coerceAtLeast(0L)
        val intervals = elapsedMs / request.intervalMs
        val prior = stops.elapsedIntervals(id) ?: 0L
        if (intervals <= prior) return
        val reduction = request.tightenBy.multiply(BigDecimal.valueOf(intervals), Money.CONTEXT)
        val distance = request.initialDistance.subtract(reduction, Money.CONTEXT).max(request.floorDistance)
        val current = stops.level(id) ?: initialStopLevel(request.side, request.entryPrice, request.initialDistance)
        val candidate = initialStopLevel(request.side, request.entryPrice, distance)
        stops.setElapsedIntervals(id, intervals)
        val tightened = isTighter(request.side, candidate, current)
        if (tightened) {
            stops.setLevel(id, candidate)
            log.info(
                "time-tightening stop advanced: order_id={} symbol={} intervals={} distance={} stop={}",
                id,
                request.symbol,
                intervals,
                distance,
                candidate,
            )
        }
        stops.dirty = true
        persist()
        if (tightened) tightenAtVenue(managed, candidate, "interval-$intervals")
    }
}
