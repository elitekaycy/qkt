package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
import com.qkt.common.TradingCalendar
import com.qkt.events.BrokerEvent
import com.qkt.marketdata.MarketPriceProvider
import java.time.Instant
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
    /**
     * Invoked when a position appears in the venue snapshot that wasn't there last tick.
     *
     * Phase 26c: the broker uses this to correlate a venue ticket back to a qkt-side
     * pending order id and emit the matching [com.qkt.events.BrokerEvent.OrderFilled].
     * Default `null` keeps existing test fixtures backward-compatible.
     */
    private val onPositionOpened: ((MT5Position) -> Unit)? = null,
    /**
     * Resolves a closed position ticket to the qkt-side (clientOrderId, strategyId)
     * pair so the synthesized [BrokerEvent.OrderFilled] flows through the per-strategy
     * filters in [com.qkt.app.TradingPipeline]. Returns null for positions opened
     * outside of this qkt session (manual user trades, another instance with the same
     * magic, pre-session positions before [MT5StateRecovery] runs).
     */
    private val closedTicketMeta: ((Long) -> ClosedPositionMeta?)? = null,
    /**
     * Returns true (consuming the marker) when qkt closed this ticket itself — e.g. via
     * `closePosition` for an active close-by-ticket. The close was already published, so the
     * poller must skip it when it sees the ticket gone, otherwise the trade is counted twice.
     */
    private val closedByEngine: ((Long) -> Boolean)? = null,
    /**
     * Best-effort source for the close price. The position snapshot diff only tells
     * us *that* a ticket closed, not at what price — the venue has already discarded
     * the position by the time we observe it gone. The latest market tick is the
     * closest proxy available without a separate deal-history API call.
     */
    private val priceProvider: MarketPriceProvider? = null,
    /**
     * Session gate: when non-null, [tick] skips the venue HTTP call whenever the
     * calendar reports out-of-session. Null keeps the legacy always-on behavior.
     * Wired by [MT5Broker] to [TradingCalendar.fxDefault] for FX/metals deployments.
     */
    private val calendar: TradingCalendar? = null,
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

    internal fun tick() {
        if (calendar != null && !calendar.isInSession("", Instant.ofEpochMilli(clock.now()))) {
            return
        }
        val current = client.getPositions(magic = profile.magic).associateBy { it.ticket }
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) {
            // qkt closed this ticket itself and already published the close — don't double-count.
            if (closedByEngine?.invoke(ticket) == true) continue
            val p = lastSnapshot[ticket] ?: continue
            val qktSymbol = "${profile.name.uppercase()}:${symbol.toQkt(p.symbol)}"
            val closeSide = if (p.type == 0) Side.SELL else Side.BUY
            val meta = closedTicketMeta?.invoke(ticket)
            val clientOrderId =
                meta?.clientOrderId
                    ?: "mt5-close-$ticket".also {
                        log.warn(
                            "MT5 poller for {} saw ticket {} close with no qkt-side meta — " +
                                "emitting close event with synthetic id and blank strategyId. " +
                                "Position was likely opened outside this qkt session.",
                            profile.name,
                            ticket,
                        )
                    }
            val strategyId = meta?.strategyId ?: ""
            val closePrice = priceProvider?.lastPrice(qktSymbol) ?: p.priceOpen
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = clientOrderId,
                    brokerOrderId = ticket.toString(),
                    symbol = qktSymbol,
                    side = closeSide,
                    price = closePrice,
                    quantity = p.volume,
                    strategyId = strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
        val opened = current.keys - lastSnapshot.keys
        for (ticket in opened) {
            val p = current[ticket] ?: continue
            onPositionOpened?.invoke(p)
        }
        lastSnapshot = current
    }
}

/**
 * Meta the poller needs to publish a useful close [BrokerEvent.OrderFilled] when a
 * ticket disappears from the venue snapshot. Populated by [MT5Broker] as positions
 * open (either synchronously from a Market/Bracket fill, or asynchronously when a
 * pending order transitions to a position).
 */
data class ClosedPositionMeta(
    val clientOrderId: String,
    val strategyId: String,
)
