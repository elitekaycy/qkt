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
import java.math.BigDecimal
import java.math.RoundingMode
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
    /**
     * Owning strategy name (single-strategy LiveSession). When set, startup state recovery
     * correlates venue-side orphan positions back to this strategy via comment-prefix match,
     * so a server-side TP/SL close fires [com.qkt.events.BrokerEvent.OrderFilled] with the
     * correct `strategyId`. Null in multi-strategy or test paths — recovery still publishes
     * [com.qkt.events.BrokerEvent.PositionReconciled] but does not seed orphan attribution.
     */
    private val strategyName: String? = null,
    /**
     * Names of other strategies sharing this MT5 magic — evaluated lazily at recovery time
     * so it picks up siblings deployed after this broker was built. Default empty (single-
     * strategy mode preserves the pre-#154 behaviour). See [MT5StateRecovery]'s docstring.
     */
    private val siblingsLookup: () -> List<String> = { emptyList() },
) : Broker {
    override val name: String = profile.name
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities
    override val supportsNativeGtd: Boolean = true

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol, priceTracker)
    internal val poller =
        MT5PositionPoller(
            client,
            profile,
            mt5Symbol,
            bus,
            clock,
            onPositionOpened = ::onPendingPositionOpened,
            closedTicketMeta = ::lookupClosedTicketMeta,
            priceProvider = priceTracker,
            calendar =
                com.qkt.common.TradingCalendar
                    .fxDefault(),
        )
    internal val pendingPoller =
        MT5PendingOrderPoller(
            client = client,
            profile = profile,
            clock = clock,
            calendar =
                com.qkt.common.TradingCalendar
                    .fxDefault(),
            onPendingDisappeared = ::onPendingDisappeared,
        )
    private val stateRecovery =
        MT5StateRecovery(
            client = client,
            profile = profile,
            symbol = mt5Symbol,
            bus = bus,
            strategyName = strategyName,
            seedOrphan = { ticket, orderId, strategyId ->
                positionMetaByTicket[ticket] = PendingMeta(orderId, strategyId)
            },
            siblingsLookup = siblingsLookup,
        )

    /**
     * Cached venue symbol metadata, keyed by broker symbol (e.g. `"XAUUSDm"`). Populated
     * lazily on first placement of a symbol via `/symbol_info`; entries never expire
     * within a broker lifetime — the venue's `volume_step` / `volume_min` don't change
     * mid-session for spot instruments. [MT5BrokerProfile.instrumentOverrides] takes
     * precedence over the cache so operators can pin values without the gateway round-trip.
     */
    private val symbolMeta: MutableMap<String, MT5SymbolInfo> = ConcurrentHashMap()

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

    /**
     * Venue-side rules for a symbol — used to quantize wire fields before placement.
     *
     * [digits] is the price scale (e.g. `3` for XAUUSD → prices in 0.001 increments).
     * MT5 rejects orders carrying more decimals than the symbol declares.
     */
    private data class VenueRules(
        val volumeStep: BigDecimal,
        val volumeMin: BigDecimal,
        val digits: Int,
        val pointSize: BigDecimal,
        val tradeStopsLevelPoints: Int,
    )

    /**
     * Outcome of the pre-placement preparation step. [Ok] carries the quantized wire
     * shape; [Reject] carries a venue-specific reason so the broker layer can emit a
     * descriptive [BrokerEvent.OrderRejected] without relying on the gateway round-trip.
     */
    private sealed interface PrepareResult {
        data class Ok(
            val wire: MT5OrderRequest,
        ) : PrepareResult

        data class Reject(
            val reason: String,
        ) : PrepareResult
    }

    /**
     * Resolve [VenueRules] for [brokerSymbol].
     *
     * Lookup order: profile overrides → in-memory cache → `/symbol_info` gateway call
     * (cached on success). Returns `null` when the gateway is unreachable AND no
     * override is configured — callers fall back to pass-through and surface the venue
     * error if the unrounded order is rejected.
     */
    private fun resolveVenueRules(brokerSymbol: String): VenueRules? {
        val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(brokerSymbol)}"
        profile.instrumentOverrides[qktSymbol]?.let { spec ->
            return VenueRules(
                volumeStep = spec.volumeStep,
                volumeMin = spec.minVolume,
                digits = spec.digits,
                pointSize = spec.pointSize,
                tradeStopsLevelPoints = spec.tradeStopsLevelPoints,
            )
        }
        symbolMeta[brokerSymbol]?.let { info ->
            return VenueRules(
                volumeStep = info.volumeStep,
                volumeMin = info.volumeMin,
                digits = info.digits,
                pointSize = info.point,
                tradeStopsLevelPoints = info.tradeStopsLevel,
            )
        }
        val fetched =
            runCatching { client.getSymbolInfo(brokerSymbol) }
                .onFailure { e ->
                    log.warn("MT5Broker ${profile.name} getSymbolInfo($brokerSymbol) failed: ${e.message}")
                }.getOrNull() ?: return null
        symbolMeta[brokerSymbol] = fetched
        return VenueRules(
            volumeStep = fetched.volumeStep,
            volumeMin = fetched.volumeMin,
            digits = fetched.digits,
            pointSize = fetched.point,
            tradeStopsLevelPoints = fetched.tradeStopsLevel,
        )
    }

    /**
     * Prepare [wire] for placement: quantize volume + prices, enforce stops level.
     *
     * Volume rounds DOWN to `volume_step`; price fields round to `digits` decimals
     * (HALF_EVEN); SL/TP within `tradeStopsLevel × pointSize` of entry are rejected
     * pre-flight. The venue would reject these anyway — surfacing locally avoids the
     * gateway round-trip and gives the strategy a structured reason string. When venue
     * rules are unavailable the original wire is passed through unchanged so the venue's
     * own rejection becomes the surfaced error.
     */
    private fun prepareForPlacement(wire: MT5OrderRequest): PrepareResult {
        val rules =
            resolveVenueRules(wire.symbol) ?: run {
                log.warn(
                    "MT5Broker ${profile.name} no venue rules for ${wire.symbol}; " +
                        "sending unrounded wire (volume=${wire.volume.toPlainString()})",
                )
                return PrepareResult.Ok(wire)
            }
        val quantizedVolume =
            if (rules.volumeStep.signum() > 0) {
                wire.volume.divide(rules.volumeStep, 0, RoundingMode.DOWN).multiply(rules.volumeStep)
            } else {
                wire.volume
            }
        if (quantizedVolume < rules.volumeMin) {
            return PrepareResult.Reject(
                "quantized volume below venue volumeMin for ${wire.symbol} (input=${wire.volume.toPlainString()})",
            )
        }
        val digits = rules.digits.coerceAtLeast(0)

        fun roundPrice(p: BigDecimal?): BigDecimal? = p?.setScale(digits, RoundingMode.HALF_EVEN)

        val quantized =
            wire.copy(
                volume = quantizedVolume,
                price = roundPrice(wire.price),
                sl = roundPrice(wire.sl),
                tp = roundPrice(wire.tp),
                stopLimit = roundPrice(wire.stopLimit),
            )
        // Stops-level enforcement: MT5 rejects orders whose SL/TP is closer to the entry
        // than `tradeStopsLevel × pointSize`. Reject locally with a structured reason so
        // strategy logs surface the cause without parsing gateway error blobs.
        if (rules.tradeStopsLevelPoints > 0 &&
            rules.pointSize.signum() > 0 &&
            quantized.price != null
        ) {
            val minDistance = rules.pointSize.multiply(BigDecimal(rules.tradeStopsLevelPoints))
            for ((field, value) in listOf("sl" to quantized.sl, "tp" to quantized.tp)) {
                if (value != null && value.signum() > 0) {
                    val distance = (quantized.price - value).abs()
                    if (distance < minDistance) {
                        return PrepareResult.Reject(
                            "$field too close to entry for ${wire.symbol}: " +
                                "distance=${distance.toPlainString()} min=${minDistance.toPlainString()} " +
                                "(tradeStopsLevel=${rules.tradeStopsLevelPoints}, pointSize=${rules.pointSize.toPlainString()})",
                        )
                    }
                }
            }
        }
        return PrepareResult.Ok(quantized)
    }

    private fun submitSingle(
        request: OrderRequest,
        wire: MT5OrderRequest,
    ): SubmitAck {
        val prepared =
            when (val result = prepareForPlacement(wire)) {
                is PrepareResult.Ok -> result.wire
                is PrepareResult.Reject -> return reject(request, result.reason)
            }
        val resp = client.placeOrder(prepared)
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
        // A Bracket with a Market entry fills synchronously like a plain Market; a Bracket
        // whose entry is Stop/Limit places a pending order on the venue and waits for the
        // position poller to surface the eventual fill. Treating every Bracket as an
        // instant fill produces a phantom OrderFilled at placement time, which marks OCO
        // siblings FILLED before either has actually triggered on MT5 and turns the
        // strategy's OCO into a hedge.
        val isInstantFill =
            request is OrderRequest.Market ||
                (request is OrderRequest.Bracket && request.entry is OrderRequest.Market)
        if (isInstantFill) {
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
        // Prepare every leg first. Prepare is pre-placement validation only; rejecting
        // here doesn't need rollback because no client.placeOrder has run yet.
        val prepared =
            composite.requests.map { wire ->
                when (val result = prepareForPlacement(wire)) {
                    is PrepareResult.Ok -> result.wire
                    is PrepareResult.Reject ->
                        return reject(request, "OCO leg ${wire.comment}: ${result.reason}")
                }
            }
        // Place legs sequentially. Each leg's ticket is registered in [pendingByTicket]
        // so [MT5PositionPoller] can correlate the eventual fill back to a strategy. If
        // any leg rejects, every previously-placed leg is cancelled on the venue and
        // the entire composite is rejected — never leave a one-legged OCO running as a
        // directional bet the caller never intended.
        val placed = mutableListOf<PlacedLeg>()
        for (wire in prepared) {
            val resp = client.placeOrder(wire)
            if (!isOrderSuccessful(resp.result.retcode)) {
                val reason =
                    "OCO leg ${wire.comment}: ${resp.errorMessage ?: "retcode=${resp.result.retcode}"}"
                log.warn("MT5Broker ${profile.name} $reason; rolling back ${placed.size} placed leg(s)")
                for (leg in placed) {
                    runCatching { client.cancelOrder(leg.ticket) }
                        .onFailure { e ->
                            log.warn(
                                "MT5Broker ${profile.name} OCO rollback cancel(${leg.ticket}) failed: ${e.message}",
                            )
                        }
                    pendingTickets.remove(leg.legOrderId)
                    pendingByTicket.remove(leg.ticket)
                }
                return reject(request, reason)
            }
            val ticket = resp.result.order
            if (ticket != 0L) {
                val legOrderId = decodeOcoLegOrderId(wire.comment) ?: request.id
                pendingTickets[legOrderId] = ticket
                pendingByTicket[ticket] = PendingMeta(legOrderId, request.strategyId)
                placed.add(PlacedLeg(ticket, legOrderId))
            }
        }

        val firstBrokerId = placed.firstOrNull { it.ticket != 0L }?.ticket?.toString() ?: composite.groupId

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

    private data class PlacedLeg(
        val ticket: Long,
        val legOrderId: String,
    )

    /**
     * Recover the per-leg client order id from an OCO wire comment.
     *
     * [MT5OrderTranslator.translateStandaloneOCO] prepends `"oco:<parent-id>/"` to each
     * leg's original comment (which is the leg's `OrderRequest.id`). The qkt-side comment
     * is full-length even though MT5 truncates to 16 chars on the venue side — we decode
     * before sending, so truncation is not an issue here.
     */
    private fun decodeOcoLegOrderId(comment: String): String? {
        if (!comment.startsWith("oco:")) return null
        val slash = comment.indexOf('/')
        if (slash < 0 || slash == comment.length - 1) return null
        return comment.substring(slash + 1)
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

    override fun getOpenPositions(): Map<String, List<com.qkt.positions.Position>> {
        val positions =
            runCatching { client.getPositions(magic = profile.magic) }
                .onFailure { e -> log.warn("MT5Broker ${profile.name} getOpenPositions failed: ${e.message}") }
                .getOrElse { return emptyMap() }
        val out: MutableMap<String, MutableList<com.qkt.positions.Position>> = mutableMapOf()
        for (p in positions) {
            val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(p.symbol)}"
            val signedQty = if (p.type == 0) p.volume else p.volume.negate()
            out.getOrPut(qktSymbol) { mutableListOf() }.add(
                com.qkt.positions.Position(
                    symbol = qktSymbol,
                    quantity = signedQty,
                    avgEntryPrice = p.priceOpen,
                ),
            )
        }
        return out
    }

    override fun recoverPendingOrders(orders: List<com.qkt.execution.ManagedOrder>) {
        if (orders.isEmpty()) return
        val pending =
            runCatching { client.getPendingOrders(magic = profile.magic) }
                .getOrElse {
                    log.warn("MT5Broker ${profile.name} recovery: getPendingOrders failed: ${it.message}")
                    return
                }
        val positions =
            runCatching { client.getPositions(magic = profile.magic) }
                .getOrElse {
                    log.warn("MT5Broker ${profile.name} recovery: getPositions failed: ${it.message}")
                    return
                }
        val actions = classifyOcoRecovery(orders, pending.map { it.ticket }.toSet(), positions)
        // Pass 1: re-seed every still-pending leg before any fill is emitted, so a
        // cancel triggered by pass 2 can resolve its sibling's ticket.
        for (a in actions) {
            if (a is OcoRecoveryAction.Reseed) {
                pendingTickets[a.order.id] = a.ticket
                pendingByTicket[a.ticket] = PendingMeta(a.order.id, a.order.request.strategyId)
                log.info(
                    "MT5Broker ${profile.name} recovery: re-seeded pending leg ${a.order.id} ticket=${a.ticket}",
                )
            }
        }
        // Pass 2: republish the fill for any leg that filled while the daemon was down;
        // OrderManager's cancel-on-fill then unwinds the still-pending sibling.
        for (a in actions) {
            if (a is OcoRecoveryAction.EmitFill) {
                pendingByTicket[a.position.ticket] = PendingMeta(a.order.id, a.order.request.strategyId)
                log.info(
                    "MT5Broker ${profile.name} recovery: leg ${a.order.id} filled during downtime " +
                        "ticket=${a.position.ticket}",
                )
                onPendingPositionOpened(a.position)
            }
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
        val meta = pendingByTicket.remove(position.ticket)
        if (meta == null) {
            // Already tracked? The Fix A cross-check in onPendingDisappeared may have
            // synthesized this fill on a prior pending-poller tick; the position-poller
            // is now seeing the same ticket in its opened-delta. Silent — already done.
            if (positionMetaByTicket.containsKey(position.ticket)) return
            log.warn(
                "MT5Broker {} saw new position ticket={} symbol={} side={} magic={} with no qkt-side " +
                    "pending meta — either externally placed or pending-poller already consumed the " +
                    "meta (poll-ordering race).",
                profile.name,
                position.ticket,
                position.symbol,
                if (position.type == 0) "BUY" else "SELL",
                profile.magic,
            )
            return
        }
        pendingTickets.remove(meta.orderId)
        // Mark the ticket as recently filled so the pending-order poller doesn't
        // mistake the subsequent "disappeared from /orders" for an external cancel.
        recentlyFilledTickets[position.ticket] = clock.now()
        // Keep the meta accessible to the position poller for the eventual close event.
        positionMetaByTicket[position.ticket] = meta
        val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(position.symbol)}"
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
        val meta = pendingByTicket[ticket] ?: return

        val ttlMs = profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
        val recentlyFilledAt = recentlyFilledTickets[ticket]
        val now = clock.now()
        if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
            pendingByTicket.remove(ticket)
            pendingTickets.entries.removeIf { it.value == ticket }
            recentlyFilledTickets.remove(ticket)
            return
        }

        // Cross-check /positions before treating as cancel. If the ticket is now a
        // position, the pending-poller observed the transition before the position-poller
        // did — synthesize the fill path here instead of phantom-cancelling.
        val asPosition =
            runCatching { client.getPositions(magic = profile.magic).firstOrNull { it.ticket == ticket } }
                .getOrNull()
        if (asPosition != null) {
            onPendingPositionOpened(asPosition)
            return
        }

        pendingByTicket.remove(ticket)
        pendingTickets.entries.removeIf { it.value == ticket }
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

    /**
     * Resolve [InstrumentMeta] for a qkt-side symbol (e.g. `EXNESS:XAUUSD`).
     *
     * Reads through the same `/symbol_info` cache that powers v0.26.3 volume quantization
     * and v0.26.4 price rounding — primes the cache on first call, hits memory after.
     * Used by [com.qkt.instrument.MT5InstrumentRegistry] so the trading pipeline gets a
     * consistent meta picture regardless of mode.
     */
    fun instrumentMeta(qktSymbol: String): com.qkt.instrument.InstrumentMeta? {
        val prefix = "${profile.name.uppercase()}:"
        val bare = qktSymbol.removePrefix(prefix)
        val brokerSymbol = mt5Symbol.toBroker(bare)
        val info =
            symbolMeta[brokerSymbol]
                ?: client.getSymbolInfo(brokerSymbol)?.also { symbolMeta[brokerSymbol] = it }
                ?: return null
        return com.qkt.instrument.InstrumentMeta(
            qktSymbol = qktSymbol,
            contractSize = info.contractSize,
            volumeStep = info.volumeStep,
            volumeMin = info.volumeMin,
            volumeMax = null,
            pointSize = info.point,
            digits = info.digits,
            tradeStopsLevelPoints = info.tradeStopsLevel,
        )
    }

    override fun shutdown() {
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
