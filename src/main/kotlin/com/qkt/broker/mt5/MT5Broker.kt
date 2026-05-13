package com.qkt.broker.mt5

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import java.util.concurrent.ConcurrentHashMap
import org.slf4j.LoggerFactory

/**
 * Routes orders to a MetaTrader 5 venue via an `mt5-gateway` HTTP service.
 *
 * Per-instance: each [com.qkt.app.LiveSession] instantiates its own broker so daemon
 * lifecycles are clean. Translates qkt [OrderRequest]s into MT5 wire shapes through
 * [MT5OrderTranslator], polls open positions through [MT5PositionPoller], and recovers
 * state on startup via [MT5StateRecovery].
 *
 * The profile determines venue identity, symbol policy (suffix translation), magic
 * number, and capability restrictions. See [MT5DefaultProfiles] for shipped templates
 * (Exness, ICMarkets, FTMO, Pepperstone) and [MT5BrokerProfileLoader] for YAML config.
 *
 * Phase 26b note: market/bracket orders publish `OrderFilled` synchronously after
 * successful placement (the venue fills immediately). Pending shapes (Stop, Limit,
 * StopLimit, TrailingStop, StandaloneOCO) publish `OrderAccepted` and rely on the
 * position poller for eventual fill detection. End-to-end pending-order lifecycle
 * (fill detection via position deltas, OCO sibling cancel-on-fill via ticket
 * correlation) is Phase 26c.
 */
class MT5Broker(
    private val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val priceTracker: MarketPriceProvider? = null,
    private val client: MT5Client =
        MT5Client(
            gatewayUrl = profile.gatewayUrl,
            tzOffsetHours = profile.serverTzOffsetHours,
            httpTimeoutMs = profile.httpTimeoutMs,
            retryAttempts = profile.retryAttempts,
        ),
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol, priceTracker)
    private val poller =
        MT5PositionPoller(
            client,
            profile,
            mt5Symbol,
            bus,
            clock,
            onPositionOpened = ::onPendingPositionOpened,
            closedTicketMeta = ::lookupClosedTicketMeta,
            priceProvider = priceTracker,
        )
    private val pendingPoller =
        MT5PendingOrderPoller(
            client,
            profile,
            onPendingDisappeared = ::onPendingDisappeared,
        )
    private val stateRecovery = MT5StateRecovery(client, profile, mt5Symbol, bus)

    /** orderId → MT5 ticket. Populated on pending placement; used by [cancel]. */
    private val pendingTickets: MutableMap<String, Long> = ConcurrentHashMap()

    /** Reverse: MT5 ticket → metadata for emitting OrderFilled when the pending fills. */
    private val pendingByTicket: MutableMap<Long, PendingMeta> = ConcurrentHashMap()

    /**
     * Open positions opened by this qkt session, keyed by MT5 ticket. Lets
     * [MT5PositionPoller] resolve a closed ticket back to (clientOrderId, strategyId)
     * when it observes the ticket disappear. Populated by:
     *   - [submitSingle] on synchronous Market/Bracket fills
     *   - [onPendingPositionOpened] when a pending order transitions to a position
     * Entries are removed when the poller publishes the close event.
     */
    private val positionMetaByTicket: MutableMap<Long, PendingMeta> = ConcurrentHashMap()

    /**
     * Tickets that just transitioned from pending → position. The pending-order poller
     * will subsequently see them disappear from `/orders`; this set disambiguates
     * "filled" (already emitted [BrokerEvent.OrderFilled]) from "external cancel."
     * Entries expire after [DISAMBIGUATION_TTL_MULTIPLIER] × [profile.pollIntervalMs].
     */
    private val recentlyFilledTickets: MutableMap<Long, Long> = ConcurrentHashMap()

    private data class PendingMeta(
        val orderId: String,
        val strategyId: String,
    )

    init {
        try {
            stateRecovery.recover()
            poller.start()
            pendingPoller.start()
        } catch (e: Exception) {
            log.warn("MT5Broker ${profile.name} startup degraded: ${e.message}")
        }
    }

    override fun supports(symbol: String): Boolean = true

    override fun submit(request: OrderRequest): SubmitAck {
        val translation =
            runCatching { translator.translate(request) }.getOrElse { ex ->
                return reject(request, ex.message ?: "translation failed")
            }

        return when (translation) {
            is MT5Translation.Single -> submitSingle(request, translation.request)
            is MT5Translation.Composite -> submitComposite(request, translation)
        }
    }

    private fun submitSingle(
        request: OrderRequest,
        wire: MT5OrderRequest,
    ): SubmitAck {
        val resp = client.placeOrder(wire)
        if (!isOrderSuccessful(resp.result.retcode)) {
            return reject(request, resp.errorMessage ?: "retcode=${resp.result.retcode}")
        }
        val brokerOrderId =
            resp.result.order
                .takeIf { it != 0L }
                ?.toString() ?: resp.result.deal.toString()
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = brokerOrderId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        // Market/Bracket fill synchronously; pending shapes wait for the position poller.
        if (request is OrderRequest.Market || request is OrderRequest.Bracket) {
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = request.id,
                    brokerOrderId = brokerOrderId,
                    symbol = request.symbol,
                    side = request.side,
                    price = resp.result.price,
                    quantity = request.quantity,
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            // Register the position ticket so [MT5PositionPoller] can resolve a close
            // event back to this strategy. Use whichever of `order` / `deal` is non-zero;
            // for instant-fill markets MT5 typically returns `order=0` and `deal=N`.
            val positionTicket =
                resp.result.order.takeIf { it != 0L }
                    ?: resp.result.deal.takeIf { it != 0L }
            if (positionTicket != null) {
                positionMetaByTicket[positionTicket] = PendingMeta(request.id, request.strategyId)
            }
        } else {
            // Pending: track ticket so we can correlate fill events and cancel by orderId.
            resp.result.order
                .takeIf { it != 0L }
                ?.let { ticket ->
                    pendingTickets[request.id] = ticket
                    pendingByTicket[ticket] = PendingMeta(request.id, request.strategyId)
                }
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = brokerOrderId,
            accepted = true,
        )
    }

    private fun submitComposite(
        request: OrderRequest,
        composite: MT5Translation.Composite,
    ): SubmitAck {
        // Submit each leg; if any leg rejects, the OCO group is degraded.
        // The OrderManager already tracks sibling relationships in `siblings[]`.
        val firstBrokerId =
            composite.requests
                .mapIndexed { _, wire ->
                    val resp = client.placeOrder(wire)
                    if (!isOrderSuccessful(resp.result.retcode)) {
                        log.warn(
                            "MT5Broker ${profile.name} OCO leg ${wire.comment} rejected: " +
                                "${resp.errorMessage ?: "retcode=${resp.result.retcode}"}",
                        )
                        null
                    } else {
                        resp.result.order
                    }
                }.firstOrNull { it != null && it != 0L }
                ?.toString() ?: composite.groupId

        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = firstBrokerId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = firstBrokerId,
            accepted = true,
        )
    }

    private fun reject(
        request: OrderRequest,
        reason: String,
    ): SubmitAck {
        bus.publish(
            BrokerEvent.OrderRejected(
                clientOrderId = request.id,
                brokerOrderId = null,
                reason = reason,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = null,
            accepted = false,
            rejectReason = reason,
        )
    }

    override fun getOpenPositions(): Map<String, com.qkt.positions.Position> {
        val positions =
            runCatching { client.getPositions(magic = profile.magic) }
                .onFailure { e -> log.warn("MT5Broker ${profile.name} getOpenPositions failed: ${e.message}") }
                .getOrElse { return emptyMap() }
        val grouped: MutableMap<String, MutableList<MT5Position>> = mutableMapOf()
        for (p in positions) {
            val qktSymbol = mt5Symbol.toQkt(p.symbol)
            grouped.getOrPut(qktSymbol) { mutableListOf() }.add(p)
        }
        return grouped.mapValues { (sym, list) ->
            // MT5 reports one row per ticket; net them into a single signed Position.
            // Sum signed quantities and compute weighted-average entry price.
            var signedQty = java.math.BigDecimal.ZERO
            var notional = java.math.BigDecimal.ZERO
            for (p in list) {
                val q = if (p.type == 0) p.volume else p.volume.negate()
                signedQty = signedQty.add(q)
                notional = notional.add(p.priceOpen.multiply(p.volume))
            }
            val absSum = list.fold(java.math.BigDecimal.ZERO) { acc, p -> acc.add(p.volume) }
            val avgPx =
                if (absSum.signum() == 0) {
                    java.math.BigDecimal.ZERO
                } else {
                    notional.divide(absSum, com.qkt.common.Money.CONTEXT)
                }
            com.qkt.positions.Position(symbol = sym, quantity = signedQty, avgEntryPrice = avgPx)
        }
    }

    override fun cancel(orderId: String) {
        val ticket = pendingTickets.remove(orderId) ?: return
        pendingByTicket.remove(ticket)
        runCatching { client.cancelOrder(ticket) }
            .onSuccess {
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = orderId,
                        brokerOrderId = ticket.toString(),
                        reason = "user cancel",
                        strategyId = "",
                        timestamp = clock.now(),
                    ),
                )
            }.onFailure { e ->
                log.warn("MT5Broker ${profile.name} cancel($orderId, ticket=$ticket) failed: ${e.message}")
            }
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val ticket =
            pendingTickets[orderId] ?: return SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "modify: no working order with id=$orderId",
            )
        val mt5Mods =
            MT5OrderModification(
                price = changes.newStopPrice ?: changes.newLimitPrice,
            )
        val resp = client.modifyOrder(ticket, mt5Mods)
        if (!isOrderSuccessful(resp.result.retcode)) {
            val reason = resp.errorMessage ?: "modify rejected: retcode=${resp.result.retcode}"
            log.warn("MT5Broker ${profile.name} modify($orderId, ticket=$ticket) rejected: $reason")
            return SubmitAck(
                clientOrderId = orderId,
                brokerOrderId = ticket.toString(),
                accepted = false,
                rejectReason = reason,
            )
        }
        bus.publish(
            BrokerEvent.OrderModified(
                clientOrderId = orderId,
                brokerOrderId = ticket.toString(),
                strategyId = "",
                timestamp = clock.now(),
            ),
        )
        return SubmitAck(
            clientOrderId = orderId,
            brokerOrderId = ticket.toString(),
            accepted = true,
        )
    }

    /**
     * Called by [MT5PositionPoller] when a venue position appears that wasn't in the
     * last snapshot. If the position's ticket matches a tracked pending order, this
     * means the pending filled — emit [BrokerEvent.OrderFilled] with the original
     * client orderId so [com.qkt.app.OrderManager] can:
     *   1. mark the order FILLED
     *   2. iterate `siblings[orderId]` and cancel any OCO siblings
     *   3. update strategy-side position state
     *
     * If the ticket isn't in [pendingByTicket], the position is external (manual user
     * trade or another qkt instance with the same magic) — ignore it; reconciliation
     * is a separate concern.
     */
    private fun onPendingPositionOpened(position: MT5Position) {
        val meta = pendingByTicket.remove(position.ticket) ?: return
        pendingTickets.remove(meta.orderId)
        // Mark the ticket as recently filled so the pending-order poller doesn't
        // mistake the subsequent "disappeared from /orders" for an external cancel.
        recentlyFilledTickets[position.ticket] = clock.now()
        // Keep the meta accessible to the position poller for the eventual close event.
        positionMetaByTicket[position.ticket] = meta
        val qktSymbol = mt5Symbol.toQkt(position.symbol)
        val filledSide = if (position.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = meta.orderId,
                brokerOrderId = position.ticket.toString(),
                symbol = qktSymbol,
                side = filledSide,
                price = position.priceOpen,
                quantity = position.volume,
                strategyId = meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    /**
     * [MT5PositionPoller] calls this when a ticket disappears from the venue snapshot
     * to resolve which qkt strategy and clientOrderId originally opened it. Removes
     * the entry on lookup — the poller publishes exactly one close event per ticket.
     */
    private fun lookupClosedTicketMeta(ticket: Long): ClosedPositionMeta? {
        val meta = positionMetaByTicket.remove(ticket) ?: return null
        return ClosedPositionMeta(clientOrderId = meta.orderId, strategyId = meta.strategyId)
    }

    /**
     * Called by [MT5PendingOrderPoller] when a tracked ticket leaves `/orders`.
     *
     * Resolves the fill-vs-cancel ambiguity:
     *
     *   1. If the ticket was very recently filled (within the TTL), [onPendingPositionOpened]
     *      already emitted [BrokerEvent.OrderFilled]. Consume the marker and exit.
     *
     *   2. Otherwise the pending was cancelled externally or its GTD expired. Emit
     *      [BrokerEvent.OrderCancelled] with a clear reason.
     *
     *   3. If we don't track this ticket, it's an external pending (manual MetaTrader
     *      placement, another qkt instance with the same magic) — ignore.
     */
    private fun onPendingDisappeared(ticket: Long) {
        val meta = pendingByTicket.remove(ticket) ?: return
        pendingTickets.entries.removeIf { it.value == ticket }
        val ttlMs = profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
        val recentlyFilledAt = recentlyFilledTickets[ticket]
        val now = clock.now()
        if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
            recentlyFilledTickets.remove(ticket)
            return
        }
        // Evict stale entries opportunistically — cheap and prevents unbounded growth
        // if positions close before their pending-disappearance signal arrives.
        recentlyFilledTickets.entries.removeIf { now - it.value >= ttlMs }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = meta.orderId,
                brokerOrderId = ticket.toString(),
                reason = "external or gtd-expired (pending disappeared from venue)",
                strategyId = meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    fun shutdown() {
        poller.stop()
        pendingPoller.stop()
    }

    companion object {
        /**
         * Multiplier applied to [MT5BrokerProfile.pollIntervalMs] for the
         * fill-vs-cancel disambiguation TTL. 3 cycles is enough headroom for the
         * position poller to tick at least once after the pending poller does.
         */
        private const val DISAMBIGUATION_TTL_MULTIPLIER: Long = 3L
    }
}
