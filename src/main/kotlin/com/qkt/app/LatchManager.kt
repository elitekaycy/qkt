package com.qkt.app

import com.qkt.common.Clock
import com.qkt.dsl.compile.CompiledLatch
import com.qkt.dsl.compile.EvalContext
import com.qkt.dsl.compile.Value
import com.qkt.execution.OrderRequest
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
    )

    private val armed = mutableListOf<ArmedLatch>()

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
            val direction =
                when {
                    tick.price >= latch.up -> 1
                    tick.price <= latch.down -> -1
                    else -> 0
                }
            if (direction == 0) continue
            val anchor = if (direction > 0) latch.up else latch.down
            fire(latch, direction, anchor)
            it.remove()
        }
    }

    private fun fire(
        latch: ArmedLatch,
        direction: Int,
        anchor: BigDecimal,
    ) {
        latch.compiled.entryBuilders.forEach { b ->
            b.build(direction, anchor, latch.ec)?.let(emit)
        }
    }
}
