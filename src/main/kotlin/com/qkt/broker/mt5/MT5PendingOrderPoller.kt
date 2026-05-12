package com.qkt.broker.mt5

import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Polls the venue's `/orders` endpoint for working (pending) orders. Detects when a
 * tracked ticket leaves the pending set — the disappearance can mean two things:
 *
 *   - The pending filled (a position opened at the same ticket). Detected by
 *     [MT5PositionPoller] via its own delta; this poller does NOT emit a fill event,
 *     it only signals "disappeared." The broker disambiguates by checking a TTL-cached
 *     "recently filled" set.
 *
 *   - The pending was cancelled (externally in MetaTrader, or by GTD expiry). The
 *     broker treats this as a real cancel and emits [com.qkt.events.BrokerEvent.OrderCancelled].
 *
 * Empty if the gateway doesn't expose `/orders` (returns 404). The poller stays harmless
 * — every tick observes "no pendings disappeared" because the snapshot starts empty
 * and stays empty.
 */
class MT5PendingOrderPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val onPendingDisappeared: ((Long) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(MT5PendingOrderPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5PendingOrder> = emptyMap()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastSnapshot = client.getPendingOrders(magic = profile.magic).associateBy { it.ticket }
        thread =
            Thread({
                while (running.get()) {
                    try {
                        Thread.sleep(profile.pollIntervalMs)
                        if (!running.get()) break
                        tick()
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        break
                    } catch (e: Exception) {
                        log.warn("MT5 pending-order poller for ${profile.name} tick failed", e)
                    }
                }
            }, "qkt-mt5-pending-poller-${profile.name}").apply {
                isDaemon = true
                start()
            }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        thread?.interrupt()
        thread?.join(5000)
        thread = null
    }

    private fun tick() {
        val current = client.getPendingOrders(magic = profile.magic).associateBy { it.ticket }
        val disappeared = lastSnapshot.keys - current.keys
        for (ticket in disappeared) {
            onPendingDisappeared?.invoke(ticket)
        }
        lastSnapshot = current
    }
}
