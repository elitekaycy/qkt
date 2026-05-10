package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import java.util.concurrent.atomic.AtomicBoolean
import org.slf4j.LoggerFactory

/**
 * Polls open MT5 positions and emits reconciliation events when they drift from local state.
 *
 * MT5 lacks a push notification for position changes, so the broker polls. The poller
 * compares the venue's open positions against its last snapshot and publishes
 * [com.qkt.events.BrokerEvent.PositionReconciled] for any difference.
 */
class MT5PositionPoller(
    private val client: MT5Client,
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val bus: EventBus,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5Position> = emptyMap()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastSnapshot = client.getPositions(magic = profile.magic).associateBy { it.ticket }
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
                        log.warn("MT5 poller for ${profile.name} tick failed", e)
                    }
                }
            }, "qkt-mt5-poller-${profile.name}").apply {
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
        val current = client.getPositions(magic = profile.magic).associateBy { it.ticket }
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) {
            val p = lastSnapshot[ticket] ?: continue
            val qktSymbol = symbol.toQkt(p.symbol)
            val closeSide = if (p.type == 0) Side.SELL else Side.BUY
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = "mt5-close-$ticket",
                    brokerOrderId = ticket.toString(),
                    symbol = qktSymbol,
                    side = closeSide,
                    price = p.priceOpen,
                    quantity = p.volume,
                    strategyId = "",
                    timestamp = clock.now(),
                ),
            )
        }
        lastSnapshot = current
    }
}
