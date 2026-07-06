package com.qkt.app

import com.qkt.common.Clock
import com.qkt.dsl.ast.LatchCloseBeyond
import com.qkt.dsl.ast.LatchFirstTick
import com.qkt.dsl.ast.LatchRetestHold
import com.qkt.dsl.ast.LatchTimeInBreach
import com.qkt.dsl.ast.NumLit
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.CompiledLatchEntry
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.Value
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.Candle
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Holds armed latches and resolves them on ticks.
 *
 * A latch arms two price wires (`ref ± offset`); the first wire a tick crosses sets the
 * direction (+1 up / -1 down) and anchor `O`, then the latch's entry builders fan out
 * concrete orders via [emit]. If no wire is crossed before the arm window elapses, the
 * latch is dropped with no orders.
 *
 * Armed latches are transient (not persisted): a restart mid-arm drops them silently and
 * the strategy re-arms on the next qualifying event.
 */
class LatchManager(
    private val emit: (OrderRequest) -> Unit,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(LatchManager::class.java)

    private data class ArmedLatch(
        val symbol: String,
        val up: BigDecimal,
        val down: BigDecimal,
        val expiresAt: Long,
        val compiled: CompiledLatch,
        val ec: EvalContext,
        var pendingDirection: Int = 0,
        var pendingSinceMs: Long? = null,
    )

    private val armed = mutableListOf<ArmedLatch>()
    private val latestPrices = mutableMapOf<String, BigDecimal>()

    /**
     * Arm [compiled] for the stream symbol extracted from [ec].
     *
     * Evaluates the reference and offset expressions immediately so the wire prices are
     * fixed at arm time. e.g. ref=2000.0, offset=0.50 → up=2000.50, down=1999.50.
     */
    fun arm(
        compiled: CompiledLatch,
        ec: EvalContext,
        now: Long = clock.now(),
    ) {
        val ref = (compiled.reference.evaluate(ec) as Value.Num).v
        val off = (compiled.offset.evaluate(ec) as Value.Num).v
        armed.add(
            ArmedLatch(
                symbol = ec.candle.symbol,
                up = ref + off,
                down = ref - off,
                expiresAt = now + compiled.armWindowMs,
                compiled = compiled,
                ec = ec,
            ),
        )
    }

    /** Check all armed latches against [tick]; fire and remove any that trip or expire. */
    fun onTick(tick: Tick) {
        latestPrices[tick.symbol] = tick.price
        if (armed.isEmpty()) return
        val it = armed.iterator()
        while (it.hasNext()) {
            val latch = it.next()
            if (latch.symbol != tick.symbol) continue
            if (tick.timestamp >= latch.expiresAt) {
                log.debug("latch expired without firing: symbol={} name={}", latch.symbol, latch.compiled.name)
                it.remove()
                continue
            }
            val fired =
                when (val confirm = latch.compiled.confirm) {
                    LatchFirstTick -> {
                        val direction = breachedDirection(latch, tick.price)
                        if (direction == 0) {
                            false
                        } else {
                            fire(latch, direction, triggerAnchor(latch, direction), tick.timestamp)
                            true
                        }
                    }
                    LatchCloseBeyond -> false
                    is LatchTimeInBreach -> timeInBreach(latch, tick.price, tick.timestamp, confirm.duration.millis)
                    is LatchRetestHold ->
                        retestHold(
                            latch = latch,
                            price = tick.price,
                            timestampMs = tick.timestamp,
                            distance =
                                (confirm.distance as? NumLit)?.value
                                    ?: error("LATCH RETEST_HOLD distance must be compile-time constant"),
                            withinMs = confirm.within.millis,
                        )
                }
            if (fired) it.remove()
        }
    }

    /** Check candle-close confirmation latches against a completed bar. */
    fun onCandle(candle: Candle) {
        latestPrices[candle.symbol] = candle.close
        if (armed.isEmpty()) return
        val it = armed.iterator()
        while (it.hasNext()) {
            val latch = it.next()
            if (latch.symbol != candle.symbol) continue
            if (candle.endTime >= latch.expiresAt) {
                log.debug("latch expired without firing: symbol={} name={}", latch.symbol, latch.compiled.name)
                it.remove()
                continue
            }
            if (latch.compiled.confirm != LatchCloseBeyond) continue
            val direction = breachedDirection(latch, candle.close)
            if (direction == 0) continue
            fire(latch, direction, triggerAnchor(latch, direction), candle.endTime)
            it.remove()
        }
    }

    private fun timeInBreach(
        latch: ArmedLatch,
        price: BigDecimal,
        timestampMs: Long,
        durationMs: Long,
    ): Boolean {
        val direction = breachedDirection(latch, price)
        if (direction == 0) {
            latch.pendingDirection = 0
            latch.pendingSinceMs = null
            return false
        }
        if (latch.pendingDirection != direction) {
            latch.pendingDirection = direction
            latch.pendingSinceMs = timestampMs
            return false
        }
        val since = latch.pendingSinceMs ?: timestampMs.also { latch.pendingSinceMs = it }
        if (timestampMs - since < durationMs) return false
        fire(latch, direction, triggerAnchor(latch, direction), timestampMs)
        return true
    }

    private fun retestHold(
        latch: ArmedLatch,
        price: BigDecimal,
        timestampMs: Long,
        distance: BigDecimal,
        withinMs: Long,
    ): Boolean {
        val breached = breachedDirection(latch, price)
        if (latch.pendingDirection == 0) {
            if (breached != 0) {
                latch.pendingDirection = breached
                latch.pendingSinceMs = timestampMs
            }
            return false
        }

        val direction = latch.pendingDirection
        val since = latch.pendingSinceMs ?: timestampMs.also { latch.pendingSinceMs = it }
        if (timestampMs - since > withinMs) {
            latch.pendingDirection = breached
            latch.pendingSinceMs = if (breached == 0) null else timestampMs
            return false
        }
        if (crossedBackThroughWire(latch, direction, price)) {
            latch.pendingDirection = breached
            latch.pendingSinceMs = if (breached == 0) null else timestampMs
            return false
        }
        if (!withinRetestBand(latch, direction, price, distance)) return false
        fire(latch, direction, triggerAnchor(latch, direction), timestampMs)
        return true
    }

    private fun breachedDirection(
        latch: ArmedLatch,
        price: BigDecimal,
    ): Int =
        when {
            price >= latch.up -> 1
            price <= latch.down -> -1
            else -> 0
        }

    private fun triggerAnchor(
        latch: ArmedLatch,
        direction: Int,
    ): BigDecimal = if (direction > 0) latch.up else latch.down

    private fun crossedBackThroughWire(
        latch: ArmedLatch,
        direction: Int,
        price: BigDecimal,
    ): Boolean = if (direction > 0) price < latch.up else price > latch.down

    private fun withinRetestBand(
        latch: ArmedLatch,
        direction: Int,
        price: BigDecimal,
        distance: BigDecimal,
    ): Boolean =
        if (direction > 0) {
            price >= latch.up && price <= latch.up + distance
        } else {
            price <= latch.down && price >= latch.down - distance
        }

    private fun fire(
        latch: ArmedLatch,
        direction: Int,
        triggerAnchor: BigDecimal,
        timestampMs: Long,
    ) {
        val ec = latch.ec.atEvaluationTime(timestampMs)
        latch.compiled.entries.forEach { entry ->
            val anchor = entryAnchor(latch, entry, triggerAnchor)
            if (anchor == null) {
                log.warn("latch entry skipped: no price snapshot for stream={}", entry.streamAlias)
                return@forEach
            }
            entry.builder.build(direction, anchor, ec)?.let(emit)
        }
    }

    private fun entryAnchor(
        latch: ArmedLatch,
        entry: CompiledLatchEntry,
        triggerAnchor: BigDecimal,
    ): BigDecimal? {
        if (entry.streamAlias == latch.compiled.streamAlias) return triggerAnchor
        val symbol = latch.ec.streams[entry.streamAlias]?.qktSymbol ?: return null
        return latestPrices[symbol]
    }
}
