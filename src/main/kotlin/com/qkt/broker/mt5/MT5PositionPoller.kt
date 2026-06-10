package com.qkt.broker.mt5

import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Side
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
     * Session gate: when non-null, [tick] skips the venue HTTP call whenever it returns false
     * (out of session). Null keeps the legacy always-on behavior. [MT5Broker] wires this to the
     * profile's [SymbolCalendars] so a multi-asset broker polls whenever any asset class is open.
     */
    private val sessionGate: ((Instant) -> Boolean)? = null,
    /**
     * Invoked once when [GATEWAY_FAILURE_ALERT_THRESHOLD] consecutive polls fail —
     * the operator-alert hook. The poller keeps skipping diffs until a clean read.
     */
    private val onGatewayUnreachable: ((Int) -> Unit)? = null,
) {
    private val log = LoggerFactory.getLogger(MT5PositionPoller::class.java)
    private val running = AtomicBoolean(false)
    private var thread: Thread? = null
    private var lastSnapshot: Map<Long, MT5Position> = emptyMap()
    private var consecutiveFailures: Int = 0

    /**
     * Tickets for which a close has already been published, mapped to the time it was
     * observed. MT5 never reuses a ticket number across positions, so once a ticket is
     * gone it cannot legitimately re-open — any later re-appearance in `/positions` is a
     * snapshot flicker. Ignoring these tickets stops the poller from re-diffing a flicker
     * into a phantom re-open and a duplicate close (which, after the meta was consumed,
     * surfaced as a blank-strategy "opened outside this session" event). Reaped after
     * [CLOSED_TICKET_RETENTION_MULTIPLIER] × [MT5BrokerProfile.pollIntervalMs] purely for
     * memory hygiene — far longer than any plausible snapshot hiccup.
     */
    private val closedTickets: MutableMap<Long, Long> = mutableMapOf()

    fun start() {
        if (!running.compareAndSet(false, true)) return
        lastSnapshot = client.getPositions(magic = profile.magic)?.associateBy { it.ticket } ?: emptyMap()
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
        if (sessionGate != null && !sessionGate(Instant.ofEpochMilli(clock.now()))) {
            return
        }
        val now = clock.now()
        closedTickets.entries.removeIf { now - it.value >= profile.pollIntervalMs * CLOSED_TICKET_RETENTION_MULTIPLIER }
        // A failed read means UNKNOWN, not "everything closed" — diffing an outage
        // snapshot would synthesize a close fill for every open position (#359).
        val snapshot =
            client.getPositions(magic = profile.magic) ?: run {
                consecutiveFailures++
                if (consecutiveFailures == GATEWAY_FAILURE_ALERT_THRESHOLD) {
                    log.error(
                        "MT5 poller for {} cannot reach the gateway ({} consecutive failures) — " +
                            "position diffs suspended until a clean read",
                        profile.name,
                        consecutiveFailures,
                    )
                    onGatewayUnreachable?.invoke(consecutiveFailures)
                }
                return
            }
        if (consecutiveFailures >= GATEWAY_FAILURE_ALERT_THRESHOLD) {
            log.info("MT5 poller for {} gateway recovered after {} failures", profile.name, consecutiveFailures)
        }
        consecutiveFailures = 0
        // A ticket we've already reported closed cannot legitimately reappear, so drop any
        // flicker that re-surfaces it before diffing — see [closedTickets].
        val current =
            snapshot
                .filter { it.ticket !in closedTickets }
                .associateBy { it.ticket }
        val closed = lastSnapshot.keys - current.keys
        for (ticket in closed) {
            closedTickets[ticket] = now
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

    private companion object {
        /** Multiples of the poll interval to retain a closed ticket before reaping. */
        const val CLOSED_TICKET_RETENTION_MULTIPLIER: Long = 100L

        /** Consecutive failed polls before [onGatewayUnreachable] fires. */
        const val GATEWAY_FAILURE_ALERT_THRESHOLD: Int = 3
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
