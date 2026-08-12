package com.qkt.broker.mt5

import com.qkt.broker.Broker
import com.qkt.broker.BrokerAccountState
import com.qkt.broker.BrokerDeal
import com.qkt.broker.BrokerPositionTicket
import com.qkt.broker.MarginLevelProvider
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.PositionAccountingMode
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.IdGenerator
import com.qkt.common.SequentialIdGenerator
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.ExitReason
import com.qkt.execution.OrderRequest
import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs
import org.slf4j.LoggerFactory

/**
 * Resolves an MT5-native stop whose trigger is already through the current executable
 * market into the same active order shape used by the engine-held trigger path.
 */
internal fun convertAlreadyCrossedStop(
    request: OrderRequest,
    currentExecPrice: BigDecimal,
): OrderRequest =
    when (request) {
        is OrderRequest.Stop ->
            if (stopIsCrossed(request.side, request.stopPrice, currentExecPrice)) {
                OrderRequest.Market(
                    id = request.id,
                    symbol = request.symbol,
                    side = request.side,
                    quantity = request.quantity,
                    timeInForce = request.timeInForce,
                    timestamp = request.timestamp,
                    strategyId = request.strategyId,
                )
            } else {
                request
            }
        is OrderRequest.StopLimit ->
            if (stopIsCrossed(request.side, request.stopPrice, currentExecPrice)) {
                OrderRequest.Limit(
                    id = request.id,
                    symbol = request.symbol,
                    side = request.side,
                    quantity = request.quantity,
                    limitPrice = request.limitPrice,
                    timeInForce = request.timeInForce,
                    timestamp = request.timestamp,
                    strategyId = request.strategyId,
                    expiresAt = request.expiresAt,
                )
            } else {
                request
            }
        is OrderRequest.Bracket -> {
            val entry = request.entry as? OrderRequest.Stop
            if (entry != null && stopIsCrossed(entry.side, entry.stopPrice, currentExecPrice)) {
                request.copy(
                    entry =
                        OrderRequest.Market(
                            id = entry.id,
                            symbol = entry.symbol,
                            side = entry.side,
                            quantity = entry.quantity,
                            timeInForce = entry.timeInForce,
                            timestamp = entry.timestamp,
                            strategyId = entry.strategyId,
                        ),
                )
            } else {
                request
            }
        }
        else -> request
    }

private fun stopIsCrossed(
    side: Side,
    stopPrice: BigDecimal,
    currentExecPrice: BigDecimal,
): Boolean = if (side == Side.BUY) stopPrice <= currentExecPrice else stopPrice >= currentExecPrice

private data class NativeStopTrigger(
    val side: Side,
    val stopPrice: BigDecimal,
)

private fun nativeStopTrigger(request: OrderRequest): NativeStopTrigger? =
    when (request) {
        is OrderRequest.Stop -> NativeStopTrigger(request.side, request.stopPrice)
        is OrderRequest.StopLimit -> NativeStopTrigger(request.side, request.stopPrice)
        is OrderRequest.Bracket ->
            (request.entry as? OrderRequest.Stop)?.let { NativeStopTrigger(it.side, it.stopPrice) }
        else -> null
    }

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
 * Market/bracket orders publish `OrderFilled` synchronously after successful placement
 * (the venue fills immediately). Pending Stop/Limit entries publish `OrderAccepted` and
 * rely on the position poller for eventual fill detection. MT5 has no venue-atomic OCO;
 * [com.qkt.app.OrderManager] places and links its pending legs separately.
 */
class MT5Broker(
    val profile: MT5BrokerProfile,
    private val bus: EventBus,
    private val clock: Clock,
    private val priceTracker: MarketPriceProvider? = null,
    private val client: MT5Client =
        MT5Client(
            gatewayUrl = profile.gatewayUrl,
            serverTimeZone = profile.serverTimeZone,
            httpTimeoutMs = profile.httpTimeoutMs,
            retryAttempts = profile.retryAttempts,
            apiKey = profile.apiKey,
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
    /**
     * Base backoff between venue queries while resolving an UNKNOWN send outcome
     * (multiplied by attempt number). Tests shrink it; production keeps the default.
     */
    private val unknownResolveBackoffMs: Long = 500L,
    /** Delay before retrying an unresolved outcome; production follows the poll cadence. */
    private val unknownPeriodicResolveMs: Long =
        maxOf(profile.pollIntervalMs, UNKNOWN_PERIODIC_RESOLVE_MIN_MS),
    /** Clean restart-recovery venue reads required before startup may continue. */
    private val recoveryReadAttempts: Int = 5,
    /** Base linear backoff between restart-recovery venue reads. */
    private val recoveryReadBackoffMs: Long = 500L,
    /**
     * Generates gateway placement ids. The default combines venue, strategy, and the
     * injected session-start time with a sequence, so ids do not restart at zero across
     * live process restarts while fixed-clock tests remain deterministic.
     */
    private val placementIds: IdGenerator =
        SequentialIdGenerator(
            prefix = "mt5-${profile.magic}-${strategyName ?: "session"}-${clock.now()}",
        ),
) : Broker,
    MarginLevelProvider {
    override val name: String = profile.name
    override val supportsPositionTickets: Boolean = true
    override val capabilities: Set<OrderTypeCapability> = profile.capabilities

    // Current mt5-gateway applies ORDER_TIME_SPECIFIED whenever expiration is present.
    override val supportsNativeGtd: Boolean = true

    private val log = LoggerFactory.getLogger(MT5Broker::class.java)
    private val unknownResolveExecutor: ScheduledExecutorService =
        Executors.newScheduledThreadPool(2) { task ->
            Thread(task, "qkt-mt5-unknown-resolve-${profile.name}").apply { isDaemon = true }
        }
    private val mt5Symbol = MT5Symbol(profile.symbolPolicy)
    private val translator = MT5OrderTranslator(profile, mt5Symbol, priceTracker)
    private val gatewayDown = AtomicBoolean(false)
    internal val poller =
        MT5PositionPoller(
            client,
            profile,
            mt5Symbol,
            bus,
            clock,
            onPositionOpened = ::onPendingPositionOpened,
            onPositionIncreased = ::onPositionIncreased,
            closedTicketMeta = ::lookupClosedTicketMeta,
            onPositionClosed = ::removeClosedTicketMeta,
            isExpectedProtectionChange = ::isExpectedProtectionChange,
            engineCloseState = ::engineCloseState,
            venueCostsForClose = ::bookVenueCloseCosts,
            priceProvider = priceTracker,
            sessionGate = profile.symbolCalendars::anyCalendarInSession,
            onGatewayUnreachable = ::publishGatewayUnreachable,
            onGatewayRecovered = ::publishGatewayRecovered,
            onPollRound = ::refreshMarginLevelIfStale,
        )
    internal val pendingPoller =
        MT5PendingOrderPoller(
            client = client,
            profile = profile,
            clock = clock,
            sessionGate = profile.symbolCalendars::anyCalendarInSession,
            onPendingDisappeared = ::onPendingDisappeared,
            onGatewayUnreachable = ::publishGatewayUnreachable,
            onGatewayRecovered = ::publishGatewayRecovered,
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
            onPositionRecovered = { position ->
                positionOpenedAtByTicket[position.ticket] = position.openTime
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
     * Positions observed before the asynchronous placement response registered their pending
     * ticket. This closes the poller-vs-HTTP callback race without treating a venue position as
     * external merely because the callback arrived a few milliseconds later.
     */
    private val earlyPositionByTicket: MutableMap<Long, MT5Position> = ConcurrentHashMap()
    private val pendingTransitionLock = Any()

    /** Entry orders whose first venue response filled only part of the requested quantity. */
    private val partialEntryByPositionTicket: MutableMap<Long, PartialEntryState> = ConcurrentHashMap()
    private val partialPositionByResidualTicket: MutableMap<Long, Long> = ConcurrentHashMap()

    /**
     * Open positions opened by this qkt session, keyed by MT5 ticket. Lets
     * [MT5PositionPoller] resolve a closed ticket back to (clientOrderId, strategyId)
     * when it observes the ticket disappear. Populated by:
     *   - [submitSingle] on synchronous Market/Bracket fills
     *   - [onPendingPositionOpened] when a pending order transitions to a position
     * Entries are removed when the poller publishes the close event.
     */
    private val positionMetaByTicket: MutableMap<Long, PendingMeta> = ConcurrentHashMap()
    private val positionSymbolByTicket: MutableMap<Long, String> = ConcurrentHashMap()
    private val positionOpenedAtByTicket: MutableMap<Long, Long> = ConcurrentHashMap()
    private val venueCostLedger =
        MT5VenueCostLedger(
            closedRetentionMs = maxOf(60_000L, profile.httpTimeoutMs + profile.pollIntervalMs * 2L),
        )

    private data class PositionProtection(
        val stopLoss: BigDecimal?,
        val takeProfit: BigDecimal?,
    )

    private val expectedProtectionByTicket: MutableMap<Long, PositionProtection> = ConcurrentHashMap()

    /**
     * Tickets that just transitioned from pending → position. The pending-order poller
     * will subsequently see them disappear from `/orders`; this set disambiguates
     * "filled" (already emitted [BrokerEvent.OrderFilled]) from "external cancel."
     * Entries expire after [DISAMBIGUATION_TTL_MULTIPLIER] × [profile.pollIntervalMs].
     */
    private val recentlyFilledTickets: MutableMap<Long, Long> = ConcurrentHashMap()

    /**
     * Tickets qkt closed itself via [submitCloseByTicket], with the time it did so. The
     * position poller consults [consumeEngineClose] before publishing a close so it does not
     * emit a second (duplicate) close when it sees one of these tickets gone. Reaped on the
     * same [DISAMBIGUATION_TTL_MULTIPLIER] × [profile.pollIntervalMs] window.
     */
    private val recentlyClosedByTicket: MutableMap<Long, EngineCloseMarker> = ConcurrentHashMap()

    private data class EngineCloseMarker(
        val startedAtMs: Long,
        val confirmed: Boolean = false,
        val confirmedAtMs: Long? = null,
    )

    private data class PendingMeta(
        val orderId: String,
        val strategyId: String,
        val protection: PositionProtection? = null,
    )

    private data class PartialEntryState(
        val meta: PendingMeta,
        val residualTicket: Long,
        val positionTicket: Long,
        val symbol: String,
        val side: Side,
        val requestedQuantity: BigDecimal,
        val cumulativeFilled: BigDecimal,
        val averageFillPrice: BigDecimal,
    )

    @Volatile
    private var accountingMode: PositionAccountingMode? = null

    init {
        if (profile.hasExpectedAccount) {
            recordAccountingMode(MT5AccountVerifier.fetchAndVerify(profile, client))
        }
        // Pollers start UNCONDITIONALLY: if recovery throws (one malformed gateway
        // response) but the pollers never start, the broker still accepts orders and
        // the session trades all day with no fill/close detection. Only recovery may
        // degrade, and loudly.
        try {
            stateRecovery.recover()
        } catch (e: Exception) {
            log.error(
                "MT5Broker ${profile.name} state recovery FAILED — orphan attribution degraded; " +
                    "positions opened before this start may close with blank strategyId",
                e,
            )
        }
        poller.start()
        pendingPoller.start()
    }

    override fun supports(symbol: String): Boolean = true

    @Volatile
    private var marginLevelCache: Pair<Long, java.math.BigDecimal?>? = null

    override fun positionAccountingMode(symbol: String): PositionAccountingMode =
        accountingMode
            ?: runCatching { client.getAccount() }
                .getOrNull()
                ?.let(::recordAccountingMode)
            ?: PositionAccountingMode.UNKNOWN

    override val supportsAccountEquity: Boolean = true

    override val supportsMarginLevel: Boolean = true

    override fun accountEquity(): java.math.BigDecimal? =
        runCatching { client.getAccount() }
            .getOrNull()
            ?.also(::recordAccountingMode)
            ?.equity

    override fun accountState(): BrokerAccountState? {
        val acct = runCatching { client.getAccount() }.getOrNull() ?: return null
        recordAccountingMode(acct)
        return BrokerAccountState(
            broker = profile.name.uppercase(),
            currency = acct.currency,
            balance = acct.balance,
            equity = acct.equity,
            margin = acct.margin,
            marginFree = acct.marginFree,
            openProfit = acct.profit,
            marginLevel = acct.marginLevel,
            login = acct.login,
            server = acct.server,
            name = acct.name,
        )
    }

    private fun recordAccountingMode(account: MT5AccountInfo): PositionAccountingMode {
        val mode =
            when (account.marginMode) {
                MARGIN_MODE_NETTING -> PositionAccountingMode.NETTING
                MARGIN_MODE_HEDGING -> PositionAccountingMode.HEDGING
                else -> PositionAccountingMode.UNKNOWN
            }
        accountingMode = mode
        return mode
    }

    override fun deals(
        from: Long,
        to: Long,
    ): List<BrokerDeal> {
        val deals = runCatching { client.getDeals(from, to) }.getOrNull() ?: return emptyList()
        // Range queries also return balance operations (deposits/withdrawals, type 2+) with
        // no symbol and zero volume — only trade deals (0=BUY, 1=SELL) belong in history.
        return deals.filter { it.type == 0 || it.type == 1 }.map { d ->
            BrokerDeal(
                broker = profile.name.uppercase(),
                dealTicket = d.ticket.toString(),
                positionTicket = d.positionTicket.takeIf { it != 0L }?.toString(),
                orderTicket = d.orderTicket.takeIf { it != 0L }?.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(d.symbol)}",
                side = if (d.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                entry = dealEntryName(d.entry),
                qty = d.volume,
                price = d.price,
                profit = d.profit,
                commission = d.commission,
                swap = d.swap,
                magic = d.magic,
                comment = d.comment,
                ts = d.timeMs,
                fee = d.fee,
                clientOrderId = d.clientOrderId,
            )
        }
    }

    override fun pendingOrders(): List<com.qkt.broker.BrokerPendingOrder> {
        val orders = runCatching { client.getPendingOrders(magic = profile.magic) }.getOrNull() ?: return emptyList()
        return orders.map { o ->
            com.qkt.broker.BrokerPendingOrder(
                ticket = o.ticket.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(o.symbol)}",
                side = if (o.type.contains("BUY")) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                orderType = o.type,
                qty = o.volume,
                price = o.priceOpen,
                stopLoss = o.sl,
                takeProfit = o.tp,
                expiresAt = o.timeExpiration.takeIf { it != 0L },
                createdAt = o.timeSetup.takeIf { it != 0L },
                magic = o.magic,
                comment = o.comment,
                clientOrderId = o.clientOrderId,
            )
        }
    }

    override fun positionTickets(): List<BrokerPositionTicket> {
        // A failed gateway read must throw, not read as "no positions": the state
        // poller prunes its ticket attributions to this list, and an empty answer
        // on a transient outage would wipe them.
        val positions =
            client.getPositions(magic = profile.magic)
                ?: error(
                    "MT5Broker ${profile.name} positionTickets: gateway read failed" +
                        client.lastReadFailure()?.let { " ($it)" }.orEmpty(),
                )
        return positions.map { p ->
            BrokerPositionTicket(
                ticket = p.ticket.toString(),
                symbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(p.symbol)}",
                side = if (p.type == 0) com.qkt.common.Side.BUY else com.qkt.common.Side.SELL,
                qty = p.volume,
                entryPrice = p.priceOpen,
                currentPrice = p.priceCurrent,
                profit = p.profit,
                swap = p.swap,
                openedAt = p.openTime,
                comment = p.comment,
                stopLoss = p.sl,
                takeProfit = p.tp,
                requestedStopLoss = positionMetaByTicket[p.ticket]?.protection?.stopLoss,
                requestedTakeProfit = positionMetaByTicket[p.ticket]?.protection?.takeProfit,
                magic = p.magic,
                clientOrderId = p.clientOrderId,
            )
        }
    }

    /**
     * Ticket → strategy-id pairs this broker currently attributes — recovery-seeded
     * orphans plus positions whose fills it has tracked. Read once at session start to
     * seed the insights ticket-attribution mirror, e.g. an orphan ticket 2832831596
     * recovered for hedge_straddle yields ("2832831596", "hedge_straddle").
     */
    fun ticketAttributions(): Map<String, String> =
        positionMetaByTicket.entries.associate { (ticket, meta) -> ticket.toString() to meta.strategyId }

    /** MT5 `DEAL_ENTRY_*` codes as names; an unrecognized code passes through as its number. */
    private fun dealEntryName(entry: Int): String =
        when (entry) {
            0 -> "IN"
            1 -> "OUT"
            2 -> "INOUT"
            3 -> "OUT_BY"
            else -> entry.toString()
        }

    /**
     * Venue margin level, cached for [MARGIN_CACHE_TTL_MS] — the margin floor consults
     * this on every entry, and a synchronous /account round-trip per order would put
     * gateway latency on the approve path. The position poller keeps the cache warm via
     * [refreshMarginLevelIfStale], so in normal operation this never leaves the cache;
     * the synchronous fetch below is the fallback for a stale cache (poller not started,
     * out of session, or gateway hiccup) — never trade a margin gate on stale data.
     */
    override fun marginLevel(): java.math.BigDecimal? {
        val now = clock.now()
        marginLevelCache?.let { (at, value) -> if (now - at < MARGIN_CACHE_TTL_MS) return value }
        val level = runCatching { client.getAccount()?.marginLevel }.getOrNull()
        marginLevelCache = now to level
        return level
    }

    /**
     * Poller-thread cache warmer: refreshes [marginLevelCache] once its TTL lapses. Only keeps
     * an already-populated cache warm — the first fetch stays on the first [marginLevel] read,
     * so sessions that never consult the margin floor never poll `/account` at all.
     */
    private fun refreshMarginLevelIfStale() {
        val (at, _) = marginLevelCache ?: return
        val now = clock.now()
        if (now - at < MARGIN_CACHE_TTL_MS) return
        val level = runCatching { client.getAccount()?.marginLevel }.getOrNull()
        marginLevelCache = now to level
    }

    private fun publishGatewayUnreachable(consecutiveFailures: Int) {
        if (!gatewayDown.compareAndSet(false, true)) return
        bus.publish(
            BrokerEvent.GatewayUnreachable(
                broker = profile.name,
                consecutiveFailures = consecutiveFailures,
                timestamp = clock.now(),
            ),
        )
    }

    private fun publishGatewayRecovered(consecutiveFailures: Int) {
        if (!gatewayDown.compareAndSet(true, false)) return
        bus.publish(
            BrokerEvent.ConnectionChanged(
                broker = profile.name,
                state = BrokerEvent.ConnectionState.RECONNECTED,
                reason = "gateway-recovered",
                consecutiveFailures = consecutiveFailures,
                timestamp = clock.now(),
            ),
        )
    }

    override fun submit(request: OrderRequest): SubmitAck {
        if (request is OrderRequest.Market && request.closesTicket != null) {
            return submitCloseByTicket(request, request.closesTicket)
        }
        val dispatchRequest = convertAlreadyCrossedStopAtMarket(request)
        val translation =
            runCatching { translator.translate(dispatchRequest) }.getOrElse { ex ->
                return reject(dispatchRequest, ex.message ?: "translation failed")
            }

        return when (translation) {
            is MT5Translation.Single -> submitSingle(dispatchRequest, translation.request)
            is MT5Translation.Composite -> submitComposite(dispatchRequest, translation)
        }
    }

    private fun convertAlreadyCrossedStopAtMarket(request: OrderRequest): OrderRequest {
        val trigger = nativeStopTrigger(request) ?: return request
        val currentExecPrice = priceTracker?.executionPrice(request.symbol, trigger.side) ?: return request
        val converted = convertAlreadyCrossedStop(request, currentExecPrice)
        if (converted == request) return request
        val target = if (request is OrderRequest.StopLimit) "LIMIT" else "MARKET"
        log.info(
            "MT5Broker {} converted already-crossed stop order_id={} side={} stop_price={} " +
                "market_price={} converted_to={}",
            profile.name,
            request.id,
            trigger.side,
            trigger.stopPrice.toPlainString(),
            currentExecPrice.toPlainString(),
            target,
        )
        return converted
    }

    /**
     * Close the venue position [ticketStr] via the gateway instead of placing an opposite
     * order. On a hedging account an opposite order opens a counter; this actually closes the
     * position. Emits the close as an attributed [BrokerEvent.OrderFilled] under [request.id]
     * so the strategy's position tracker realizes it, and marks the ticket via
     * [recentlyClosedByTicket] so the position poller does not publish a duplicate close.
     *
     * Non-blocking like [submitSingle]'s market path: the HTTP send runs on OkHttp's dispatcher
     * and the outcome returns as bus events — closes ride CLOSE rules, trailing-stop fires, and
     * flattens on the engine thread, where a blocking round-trip stalls tick processing exactly
     * when exits matter. The poller-suppression mark is set BEFORE the send (the poller could
     * observe the position gone before our callback runs) and rolled back on failure.
     */
    private fun submitCloseByTicket(
        request: OrderRequest.Market,
        ticketStr: String,
    ): SubmitAck {
        val ticket =
            ticketStr.toLongOrNull()
                ?: return reject(request, "closesTicket is not a valid ticket: $ticketStr")
        val brokerSymbol = mt5Symbol.toBroker(request.symbol.substringAfter(':'))
        val closeQuantity =
            when (val result = prepareVolume(brokerSymbol, request.quantity)) {
                is VolumeResult.Ok -> result.quantity
                is VolumeResult.Reject -> return reject(request, result.reason)
            }
        val closeStartedAtMs = clock.now()
        recentlyClosedByTicket[ticket] = EngineCloseMarker(closeStartedAtMs)
        client.closePositionAsync(ticket, volume = closeQuantity, partial = request.partialClose) { resp ->
            if (!isOrderSuccessful(resp.result.retcode)) {
                val message = resp.errorMessage ?: "close_position retcode=${resp.result.retcode}"
                if (isAmbiguousSendFailure(message)) {
                    executeUnknownResolution {
                        resolveUnknownCloseOutcome(request, ticket, closeQuantity, closeStartedAtMs, message)
                    }
                    return@closePositionAsync
                }
                recentlyClosedByTicket.remove(ticket)
                reject(request, message)
                return@closePositionAsync
            }
            val partiallyFilled = resp.result.retcode == MT5_TRADE_RETCODE_DONE_PARTIAL
            val reportedVolume = resp.result.volume?.takeIf { it.signum() > 0 }
            if (partiallyFilled && reportedVolume == null) {
                // The venue changed state, so a rejection would invite a duplicate close.
                // Keep the order accepted-but-unresolved and let the position poller
                // reconcile the remaining venue quantity without sending a second close.
                // Remove the pending marker before it can be confirmed: a poll that sees a
                // confirmed marker adopts the reduced snapshot without publishing its delta.
                recentlyClosedByTicket.remove(ticket)
                bus.publish(
                    BrokerEvent.OrderAccepted(
                        clientOrderId = request.id,
                        brokerOrderId = ticket.toString(),
                        strategyId = request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
                log.error(
                    "MT5Broker {} partial close {} omitted actual filled volume",
                    profile.name,
                    request.id,
                )
                return@closePositionAsync
            }
            val positionRemainsOpen = request.partialClose || partiallyFilled
            if (positionRemainsOpen) {
                confirmEngineClose(ticket)
            } else {
                confirmEngineClose(ticket)
                positionMetaByTicket.remove(ticket)
                positionSymbolByTicket.remove(ticket)
            }
            val filledQuantity = reportedVolume ?: closeQuantity
            val venueCosts =
                venueCostsForPositionClose(
                    positionTicket = ticket,
                    closingDealTicket = resp.result.deal,
                    positionClosed = !positionRemainsOpen,
                )
            if (!positionRemainsOpen) positionOpenedAtByTicket.remove(ticket)
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = request.id,
                    brokerOrderId = ticket.toString(),
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = request.id,
                    brokerOrderId = ticket.toString(),
                    symbol = request.symbol,
                    side = request.side,
                    price = resp.result.price,
                    quantity = filledQuantity,
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                    venueCosts = venueCosts,
                    exitReason = ExitReason.CLOSE,
                ),
            )
        }
        return SubmitAck(request.id, ticket.toString(), accepted = true)
    }

    private fun resolveUnknownCloseOutcome(
        request: OrderRequest.Market,
        ticket: Long,
        requestedQuantity: BigDecimal,
        closeStartedAtMs: Long,
        cause: String,
    ) {
        log.warn(
            "MT5Broker {} close {} outcome UNKNOWN ({}) — querying venue before resolving",
            profile.name,
            request.id,
            cause,
        )
        var cleanAbsenceReads = 0
        for (attempt in 1..UNKNOWN_RESOLVE_ATTEMPTS) {
            Thread.sleep(unknownResolveBackoffMs * attempt)
            val positions = client.getPositions(magic = profile.magic) ?: continue
            val deals =
                client.getPositionDeals(
                    positionTicket = ticket,
                    fromUtcMs = closeStartedAtMs - UNKNOWN_CORRELATION_WINDOW_MS,
                    toUtcMs = maxOf(clock.now(), closeStartedAtMs) + UNKNOWN_CORRELATION_WINDOW_MS,
                ) ?: continue
            val position = positions.firstOrNull { it.ticket == ticket }
            val closingDeals =
                deals
                    .filter {
                        it.positionTicket == ticket &&
                            it.magic == profile.magic &&
                            it.entry != 0 &&
                            it.timeMs >= closeStartedAtMs - CLOSE_DEAL_CLOCK_SKEW_MS
                    }.sortedBy { it.timeMs }
            if (closingDeals.isNotEmpty()) {
                val filledQuantity = closingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
                val fillPrice = weightedDealPrice(closingDeals)
                if (filledQuantity.signum() > 0 && fillPrice != null) {
                    val positionRemainsOpen = position != null
                    confirmEngineClose(ticket)
                    if (!positionRemainsOpen) {
                        positionMetaByTicket.remove(ticket)
                        positionSymbolByTicket.remove(ticket)
                        positionOpenedAtByTicket.remove(ticket)
                    }
                    val venueCosts =
                        venueCostLedger.book(
                            ticket,
                            deals,
                            positionClosed = !positionRemainsOpen,
                            nowMs = clock.now(),
                        )
                    if (poller.hasPublishedClose(ticket)) {
                        log.info(
                            "MT5Broker {} close {} was already published by the position poller for ticket {}",
                            profile.name,
                            request.id,
                            ticket,
                        )
                        return
                    }
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    bus.publish(
                        BrokerEvent.OrderFilled(
                            clientOrderId = request.id,
                            brokerOrderId = ticket.toString(),
                            symbol = request.symbol,
                            side = request.side,
                            price = fillPrice,
                            quantity = filledQuantity.min(requestedQuantity),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                            venueCosts = venueCosts,
                            exitReason = ExitReason.CLOSE,
                        ),
                    )
                    log.info(
                        "MT5Broker {} close {} resolved as FILLED ticket {}",
                        profile.name,
                        request.id,
                        ticket,
                    )
                    return
                }
            }
            if (position != null) cleanAbsenceReads++
        }
        if (cleanAbsenceReads == UNKNOWN_RESOLVE_ATTEMPTS) {
            recentlyClosedByTicket.remove(ticket)
            reject(request, "unknown-state close resolved as not executed after verified retry window ($cause)")
            return
        }
        log.error(
            "MT5Broker {} close {} outcome UNRESOLVED after {} venue queries — no rejection emitted",
            profile.name,
            request.id,
            UNKNOWN_RESOLVE_ATTEMPTS,
        )
        publishGatewayUnreachable(UNKNOWN_RESOLVE_ATTEMPTS)
        scheduleUnknownCloseResolution(request, ticket, requestedQuantity, closeStartedAtMs, cause)
    }

    private fun scheduleUnknownCloseResolution(
        request: OrderRequest.Market,
        ticket: Long,
        requestedQuantity: BigDecimal,
        closeStartedAtMs: Long,
        cause: String,
    ) {
        scheduleUnknownResolution {
            resolveUnknownCloseOutcome(request, ticket, requestedQuantity, closeStartedAtMs, cause)
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
        val volumeMax: BigDecimal?,
        val digits: Int,
        val pointSize: BigDecimal,
        val tradeStopsLevelPoints: Int,
        val tradeFreezeLevelPoints: Int,
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

    private sealed interface VolumeResult {
        data class Ok(
            val quantity: BigDecimal,
        ) : VolumeResult

        data class Reject(
            val reason: String,
        ) : VolumeResult
    }

    private fun prepareVolume(
        brokerSymbol: String,
        quantity: BigDecimal,
    ): VolumeResult {
        val rules = resolveVenueRules(brokerSymbol) ?: return VolumeResult.Ok(quantity)
        val quantized =
            if (rules.volumeStep.signum() > 0) {
                quantity.divide(rules.volumeStep, 0, RoundingMode.DOWN).multiply(rules.volumeStep)
            } else {
                quantity
            }
        return when {
            quantized < rules.volumeMin ->
                VolumeResult.Reject(
                    "quantized volume below venue volumeMin for $brokerSymbol (input=${quantity.toPlainString()})",
                )
            rules.volumeMax != null && quantized > rules.volumeMax ->
                VolumeResult.Reject(
                    "quantized volume above venue volumeMax for $brokerSymbol (input=${quantity.toPlainString()})",
                )
            else -> VolumeResult.Ok(quantized)
        }
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
                volumeMax = spec.maxVolume,
                digits = spec.digits,
                pointSize = spec.pointSize,
                tradeStopsLevelPoints = spec.tradeStopsLevelPoints,
                tradeFreezeLevelPoints = 0,
            )
        }
        symbolMeta[brokerSymbol]?.let { info ->
            return VenueRules(
                volumeStep = info.volumeStep,
                volumeMin = info.volumeMin,
                volumeMax = info.volumeMax,
                digits = info.digits,
                pointSize = info.point,
                tradeStopsLevelPoints = info.tradeStopsLevel,
                tradeFreezeLevelPoints = info.tradeFreezeLevel,
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
            volumeMax = fetched.volumeMax,
            digits = fetched.digits,
            pointSize = fetched.point,
            tradeStopsLevelPoints = fetched.tradeStopsLevel,
            tradeFreezeLevelPoints = fetched.tradeFreezeLevel,
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
        if (rules.volumeMax != null && quantizedVolume > rules.volumeMax) {
            return PrepareResult.Reject(
                "quantized volume above venue volumeMax for ${wire.symbol} (input=${wire.volume.toPlainString()})",
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
            val current = priceTracker?.lastPrice("${profile.name.uppercase()}:${mt5Symbol.toQkt(wire.symbol)}")
            if (current != null && (quantized.price - current).abs() < minDistance) {
                return PrepareResult.Reject(
                    "entry too close to current price for ${wire.symbol}: " +
                        "distance=${(quantized.price - current).abs().toPlainString()} " +
                        "min=${minDistance.toPlainString()}",
                )
            }
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
        // #185 diagnostic: the gateway rejects a STOP entry whose trigger sits the wrong side
        // of the live quote (BUY_STOP <= ask). Log the submitted trigger vs the last market
        // price we saw, so a rejection's stale-quote delta is visible — without a fresh
        // getTick, which would add the signal-to-submission latency that causes the staleness.
        if ("STOP" in prepared.type) {
            log.info(
                "STOP submit {} type={} price={} lastSeen={}",
                request.id,
                prepared.type,
                prepared.price?.toPlainString(),
                priceTracker?.lastPrice(request.symbol)?.toPlainString(),
            )
        }
        // Non-blocking placement: the HTTP send runs on OkHttp's dispatcher and the venue's
        // result returns as bus events via [handlePlacementResult] (rerouted onto the engine
        // thread by the single-consumer loop). submit returns an optimistic ack at once so the
        // engine thread never waits on the order round-trip — the real accept/reject/fill
        // follows on the bus, which is what the event-driven OCO/OTO sequencing consumes.
        val placement = prepared.withPlacementId()
        val placementStartedAtMs = clock.now()
        val protection = protectionOf(placement)
        client.placeOrderAsync(placement) { resp ->
            handlePlacementResult(request, placement, placementStartedAtMs, protection, resp)
        }
        return SubmitAck(
            clientOrderId = request.id,
            brokerOrderId = null,
            accepted = true,
        )
    }

    /**
     * Turn the venue's placement response into bus events. Runs on an OkHttp dispatcher thread
     * (off the engine thread); every `bus.publish` here is rerouted onto the engine thread by
     * the single-consumer loop, and the ticket maps it mutates are concurrent. A bad retcode
     * becomes [BrokerEvent.OrderRejected]; success becomes [BrokerEvent.OrderAccepted] plus, for
     * an instant-fill market, [BrokerEvent.OrderFilled].
     * A venue partial instead emits [BrokerEvent.OrderPartiallyFilled] and retains the residual
     * ticket for position-poller reconciliation.
     */
    private fun handlePlacementResult(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: PositionProtection?,
        resp: MT5OrderResponse,
    ) {
        if (!isOrderSuccessful(resp.result.retcode)) {
            val message = resp.errorMessage
            // An IO error or gateway 5xx AFTER the send leaves the outcome unknown — the
            // order may have reached MT5 and filled. Telling the strategy "rejected"
            // makes it re-fire and double the position; resolve against venue truth first.
            if (message != null && isAmbiguousSendFailure(message)) {
                executeUnknownResolution {
                    resolveUnknownOutcome(request, placement, placementStartedAtMs, protection, message)
                }
                return
            }
            reject(request, message ?: "retcode=${resp.result.retcode}")
            return
        }
        val brokerOrderId =
            resp.result.order
                .takeIf { it != 0L }
                ?.toString() ?: resp.result.deal.toString()
        // A Bracket with a Market entry fills synchronously like a plain Market; a Bracket
        // whose entry is Stop/Limit places a pending order on the venue and waits for the
        // position poller to surface the eventual fill. Treating every Bracket as an
        // instant fill produces a phantom OrderFilled at placement time, which marks OCO
        // siblings FILLED before either has actually triggered on MT5 and turns the
        // strategy's OCO into a hedge.
        val isInstantFill =
            request is OrderRequest.Market ||
                (request is OrderRequest.Bracket && request.entry is OrderRequest.Market)
        val isPartialEntry = isInstantFill && resp.result.retcode == MT5_TRADE_RETCODE_DONE_PARTIAL
        val partialQuantity = resp.result.volume
        if (
            isPartialEntry &&
            (
                partialQuantity == null ||
                    partialQuantity.signum() != 1 ||
                    partialQuantity >= placement.volume ||
                    resp.result.order == 0L ||
                    resp.result.deal == 0L
            )
        ) {
            executeUnknownResolution {
                resolveUnknownOutcome(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    "partial fill response cannot identify a positive residual order",
                )
            }
            return
        }
        if (isPartialEntry) {
            executeUnknownResolution {
                resolvePartialPlacement(
                    request = request,
                    placement = placement,
                    placementStartedAtMs = placementStartedAtMs,
                    protection = protection,
                    response = resp,
                )
            }
            return
        }
        // Register the venue ticket BEFORE announcing acceptance so any consumer reacting to
        // [BrokerEvent.OrderAccepted] (e.g. a follow-up modify keyed by clientOrderId) sees the
        // broker's bookkeeping already consistent.
        if (isInstantFill) {
            // Use whichever of `order` / `deal` is non-zero; instant-fill markets typically
            // return `order=0` and `deal=N`. Lets [MT5PositionPoller] attribute the close.
            val positionTicket =
                resp.result.order.takeIf { it != 0L }
                    ?: resp.result.deal.takeIf { it != 0L }
            if (positionTicket != null) {
                positionMetaByTicket[positionTicket] = PendingMeta(request.id, request.strategyId, protection)
                positionSymbolByTicket[positionTicket] = request.symbol
                positionOpenedAtByTicket[positionTicket] = clock.now()
            }
        } else {
            // Pending: track ticket so we can correlate fill events and cancel by orderId.
            resp.result.order
                .takeIf { it != 0L }
                ?.let { ticket ->
                    registerPendingTicket(ticket, PendingMeta(request.id, request.strategyId, protection))
                }
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = brokerOrderId,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        if (isInstantFill) {
            val filledQuantity =
                resp.result.volume?.takeIf { it.signum() > 0 }
                    ?: placement.volume
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = request.id,
                    brokerOrderId = brokerOrderId,
                    symbol = request.symbol,
                    side = request.side,
                    price = resp.result.price,
                    quantity = filledQuantity,
                    strategyId = request.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }

    private fun venueCostsForPositionClose(
        positionTicket: Long,
        closingDealTicket: Long,
        positionClosed: Boolean,
    ): BigDecimal {
        val now = clock.now()
        val from = positionOpenedAtByTicket[positionTicket] ?: now - DEAL_LOOKUP_WINDOW_MS
        val deals =
            client.getPositionDeals(positionTicket, fromUtcMs = from, toUtcMs = now)
                ?: client
                    .getDeals(
                        fromUtcMs = now - DEAL_LOOKUP_WINDOW_MS,
                        toUtcMs = now + DEAL_LOOKUP_WINDOW_MS,
                    ).orEmpty()
                    .filter {
                        it.positionTicket == positionTicket || it.ticket == closingDealTicket
                    }
        return venueCostLedger.book(positionTicket, deals, positionClosed, now)
    }

    /**
     * MqlTradeResult separates order and deal tickets and exposes no position ticket. Resolve
     * the exact deal through venue history, whose `position_id` is the authoritative key used by
     * `/get_positions`; never infer that the residual order ticket owns the same numeric id.
     */
    private fun resolvePartialPlacement(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: PositionProtection?,
        response: MT5OrderResponse,
    ) {
        for (attempt in 1..UNKNOWN_RESOLVE_ATTEMPTS) {
            val deals =
                client.getDeals(
                    fromUtcMs = placementStartedAtMs - UNKNOWN_CORRELATION_WINDOW_MS,
                    toUtcMs = maxOf(clock.now(), placementStartedAtMs) + UNKNOWN_CORRELATION_WINDOW_MS,
                )
            val openingDeal =
                deals
                    ?.singleOrNull {
                        it.ticket == response.result.deal &&
                            it.entry == 0 &&
                            it.positionTicket > 0L &&
                            (it.orderTicket == 0L || it.orderTicket == response.result.order) &&
                            it.magic == profile.magic &&
                            it.symbol == placement.symbol &&
                            it.type == (if (request.side == Side.BUY) 0 else 1)
                    }
            if (openingDeal != null) {
                publishInitialPartialEntry(
                    request,
                    placement,
                    placementStartedAtMs,
                    protection,
                    response,
                    openingDeal,
                )
                return
            }
            if (attempt < UNKNOWN_RESOLVE_ATTEMPTS) {
                try {
                    Thread.sleep(unknownResolveBackoffMs * attempt)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    return
                }
            }
        }
        log.error(
            "MT5Broker {} partial entry {} cannot resolve deal {} to a position ticket; " +
                "retaining UNKNOWN outcome and retrying without assuming order-ticket identity",
            profile.name,
            request.id,
            response.result.deal,
        )
        publishGatewayUnreachable(UNKNOWN_RESOLVE_ATTEMPTS)
        scheduleUnknownResolution {
            resolvePartialPlacement(request, placement, placementStartedAtMs, protection, response)
        }
    }

    private fun publishInitialPartialEntry(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: PositionProtection?,
        response: MT5OrderResponse,
        openingDeal: MT5Deal,
    ) {
        val residualTicket = response.result.order
        val positionTicket = openingDeal.positionTicket
        val filledQuantity = requireNotNull(response.result.volume)
        val meta = PendingMeta(request.id, request.strategyId, protection)
        val earlyPosition =
            registerPartialEntry(
                PartialEntryState(
                    meta = meta,
                    residualTicket = residualTicket,
                    positionTicket = positionTicket,
                    symbol = request.symbol,
                    side = request.side,
                    requestedQuantity = placement.volume,
                    cumulativeFilled = filledQuantity,
                    averageFillPrice = response.result.price,
                ),
                openedAtMs = openingDeal.timeMs.takeIf { it > 0L } ?: placementStartedAtMs,
            )
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = residualTicket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderPartiallyFilled(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = response.result.price,
                quantity = filledQuantity,
                cumulativeFilled = filledQuantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        earlyPosition?.let(::reconcilePartialEntry)
        if (partialPositionByResidualTicket.containsKey(residualTicket)) {
            pendingPoller.seedTrackedTickets(setOf(residualTicket))
        }
    }

    private fun registerPartialEntry(
        state: PartialEntryState,
        openedAtMs: Long,
    ): MT5Position? =
        synchronized(pendingTransitionLock) {
            pendingTickets[state.meta.orderId] = state.residualTicket
            pendingByTicket[state.residualTicket] = state.meta
            partialEntryByPositionTicket[state.positionTicket] = state
            partialPositionByResidualTicket[state.residualTicket] = state.positionTicket
            positionMetaByTicket[state.positionTicket] = state.meta
            positionSymbolByTicket[state.positionTicket] = state.symbol
            positionOpenedAtByTicket[state.positionTicket] = openedAtMs
            earlyPositionByTicket.remove(state.positionTicket)
        }

    private fun bookVenueCloseCosts(
        positionTicket: Long,
        deals: List<MT5Deal>,
        positionClosed: Boolean,
    ): BigDecimal {
        if (positionClosed) positionOpenedAtByTicket.remove(positionTicket)
        return venueCostLedger.book(positionTicket, deals, positionClosed = positionClosed, nowMs = clock.now())
    }

    /** True for failures where the request may have reached the venue despite the error. */
    private fun isAmbiguousSendFailure(errorMessage: String): Boolean =
        errorMessage.startsWith("IO error") ||
            errorMessage.startsWith("HTTP 409") ||
            errorMessage.startsWith("HTTP 5") ||
            errorMessage.startsWith("invalid gateway response after send")

    /**
     * Resolve an UNKNOWN send outcome by querying the venue for an order carrying this
     * request's full gateway placement id, with a constrained fallback to the venue-truncated
     * comment for older gateways.
     *
     *   - Found as a pending → the venue owns it: register tickets, publish Accepted.
     *   - Found as a position → it filled: register meta, publish Accepted + Filled.
     *   - Repeated clean order/position/deal reads with no match → publish Rejected.
     *   - Found only in deal history → replay its fill and any completed close legs.
     *   - Reads keep failing → leave the order UNRESOLVED (no event): a false "rejected"
     *     invites a duplicate submission, which is the worse failure. Alert the operator.
     */
    private fun resolveUnknownOutcome(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: PositionProtection?,
        cause: String,
    ) {
        val wireComment = placement.comment.take(MT5_COMMENT_MAX_LENGTH)
        val brokerSymbol = mt5Symbol.toBroker(request.symbol.substringAfter(':'))
        log.warn(
            "MT5Broker {} order {} outcome UNKNOWN ({}) — querying venue before resolving",
            profile.name,
            request.id,
            cause,
        )
        var cleanAbsenceReads = 0
        for (attempt in 1..UNKNOWN_RESOLVE_ATTEMPTS) {
            Thread.sleep(unknownResolveBackoffMs * attempt)
            val pendings = client.getPendingOrders(magic = profile.magic) ?: continue
            val positions = client.getPositions(magic = profile.magic) ?: continue
            val pendingCandidates =
                pendings.filter {
                    it.ticket > 0L &&
                        !pendingByTicket.containsKey(it.ticket) &&
                        it.symbol == brokerSymbol &&
                        matchesComment(it.comment, wireComment)
                }
            val positionCandidates =
                positions.filter {
                    !positionMetaByTicket.containsKey(it.ticket) &&
                        it.symbol == brokerSymbol &&
                        matchesComment(it.comment, wireComment)
                }
            val exactMatches: List<UnknownVenueMatch> =
                pendingCandidates
                    .filter { it.clientOrderId == placement.clientOrderId }
                    .map { UnknownVenueMatch.Pending(it) } +
                    positionCandidates
                        .filter { it.clientOrderId == placement.clientOrderId }
                        .map { UnknownVenueMatch.Position(it) }
            val fallbackMatches: List<UnknownVenueMatch> =
                if (exactMatches.isEmpty()) {
                    pendingCandidates
                        .filter { matchesUnknownPending(it, placement, placementStartedAtMs) }
                        .map { UnknownVenueMatch.Pending(it) } +
                        positionCandidates
                            .filter { matchesUnknownPosition(it, placement, placementStartedAtMs) }
                            .map { UnknownVenueMatch.Position(it) }
                } else {
                    emptyList()
                }
            val matches = if (exactMatches.isNotEmpty()) exactMatches else fallbackMatches
            if (matches.size > 1 || (matches.isEmpty() && (pendingCandidates + positionCandidates).isNotEmpty())) {
                log.error(
                    "MT5Broker {} order {} UNKNOWN outcome remains ambiguous on attempt {}/{}: " +
                        "pendingCandidates={} positionCandidates={} correlatedMatches={}",
                    profile.name,
                    request.id,
                    attempt,
                    UNKNOWN_RESOLVE_ATTEMPTS,
                    pendingCandidates.map { it.ticket },
                    positionCandidates.map { it.ticket },
                    matches.size,
                )
                continue
            }
            when (val match = matches.singleOrNull()) {
                is UnknownVenueMatch.Pending -> {
                    val pendingMatch = match.order
                    registerPendingTicket(
                        pendingMatch.ticket,
                        PendingMeta(request.id, request.strategyId, protection),
                    )
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = pendingMatch.ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    log.info(
                        "MT5Broker {} order {} resolved as PENDING ticket {}",
                        profile.name,
                        request.id,
                        pendingMatch.ticket,
                    )
                    return
                }
                is UnknownVenueMatch.Position -> {
                    val positionMatch = match.position
                    positionMetaByTicket[positionMatch.ticket] =
                        PendingMeta(request.id, request.strategyId, protection)
                    positionSymbolByTicket[positionMatch.ticket] = request.symbol
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = request.id,
                            brokerOrderId = positionMatch.ticket.toString(),
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    bus.publish(
                        BrokerEvent.OrderFilled(
                            clientOrderId = request.id,
                            brokerOrderId = positionMatch.ticket.toString(),
                            symbol = request.symbol,
                            side = request.side,
                            price = positionMatch.priceOpen,
                            quantity = positionMatch.volume,
                            strategyId = request.strategyId,
                            timestamp = clock.now(),
                        ),
                    )
                    log.info(
                        "MT5Broker {} order {} resolved as FILLED ticket {}",
                        profile.name,
                        request.id,
                        positionMatch.ticket,
                    )
                    return
                }
                null -> {
                    val deals =
                        client.getDeals(
                            fromUtcMs = placementStartedAtMs - UNKNOWN_CORRELATION_WINDOW_MS,
                            toUtcMs = maxOf(clock.now(), placementStartedAtMs) + UNKNOWN_CORRELATION_WINDOW_MS,
                        ) ?: continue
                    val dealCandidates =
                        deals.filter {
                            it.entry == 0 &&
                                it.magic == profile.magic &&
                                it.symbol == brokerSymbol &&
                                !positionMetaByTicket.containsKey(it.positionTicket) &&
                                (
                                    it.clientOrderId == placement.clientOrderId ||
                                        matchesComment(it.comment, wireComment)
                                )
                        }
                    val exactDealGroups =
                        dealCandidates
                            .filter { it.clientOrderId == placement.clientOrderId }
                            .groupBy(::dealPositionKey)
                    val fallbackDealGroups =
                        if (exactDealGroups.isEmpty()) {
                            dealCandidates
                                .groupBy(::dealPositionKey)
                                .filterValues { matchesUnknownDeals(it, placement, placementStartedAtMs) }
                        } else {
                            emptyMap()
                        }
                    val dealGroups = if (exactDealGroups.isNotEmpty()) exactDealGroups else fallbackDealGroups
                    if (dealGroups.size > 1 || (dealGroups.isEmpty() && dealCandidates.isNotEmpty())) {
                        log.error(
                            "MT5Broker {} order {} deal-history outcome remains ambiguous on attempt {}/{}: {}",
                            profile.name,
                            request.id,
                            attempt,
                            UNKNOWN_RESOLVE_ATTEMPTS,
                            dealCandidates.map { it.ticket },
                        )
                        continue
                    }
                    val openingDeals = dealGroups.values.singleOrNull()
                    if (openingDeals != null) {
                        if (resolveUnknownDeals(request, protection, openingDeals, deals, positions)) return
                        continue
                    }
                    cleanAbsenceReads++
                }
            }
        }
        if (cleanAbsenceReads == UNKNOWN_RESOLVE_ATTEMPTS) {
            reject(request, "unknown-state send resolved as not placed after verified retry window ($cause)")
            return
        }
        log.error(
            "MT5Broker {} order {} send outcome UNRESOLVED after {} venue queries — " +
                "no event emitted (a false reject invites a duplicate). Check the venue manually.",
            profile.name,
            request.id,
            UNKNOWN_RESOLVE_ATTEMPTS,
        )
        publishGatewayUnreachable(UNKNOWN_RESOLVE_ATTEMPTS)
        scheduleUnknownPlacementResolution(request, placement, placementStartedAtMs, protection, cause)
    }

    private fun scheduleUnknownPlacementResolution(
        request: OrderRequest,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
        protection: PositionProtection?,
        cause: String,
    ) {
        scheduleUnknownResolution {
            resolveUnknownOutcome(request, placement, placementStartedAtMs, protection, cause)
        }
    }

    private fun executeUnknownResolution(task: () -> Unit) {
        try {
            unknownResolveExecutor.execute(task)
        } catch (failure: RejectedExecutionException) {
            if (!unknownResolveExecutor.isShutdown) throw failure
        }
    }

    private fun scheduleUnknownResolution(task: () -> Unit) {
        try {
            unknownResolveExecutor.schedule(
                task,
                unknownPeriodicResolveMs,
                TimeUnit.MILLISECONDS,
            )
        } catch (failure: RejectedExecutionException) {
            if (!unknownResolveExecutor.isShutdown) throw failure
        }
    }

    private fun dealPositionKey(deal: MT5Deal): Long =
        deal.positionTicket.takeIf { it > 0L }
            ?: deal.orderTicket.takeIf { it > 0L }
            ?: deal.ticket

    private fun matchesUnknownDeals(
        deals: List<MT5Deal>,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean {
        val expectedType = if (placement.type.startsWith("BUY")) 0 else 1
        val totalVolume = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        return deals.all { it.type == expectedType } &&
            totalVolume.compareTo(placement.volume) == 0 &&
            deals.any { isNearPlacement(it.timeMs, placementStartedAtMs) }
    }

    /** Replay a deal-proven ambiguous placement, including its close legs when already flat. */
    private fun resolveUnknownDeals(
        request: OrderRequest,
        protection: PositionProtection?,
        openingDeals: List<MT5Deal>,
        allDeals: List<MT5Deal>,
        positions: List<MT5Position>,
    ): Boolean {
        val positionTicket = dealPositionKey(openingDeals.first())
        if (positionTicket <= 0L) return false
        val quantity = openingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (quantity.signum() <= 0) return false
        val price = weightedDealPrice(openingDeals) ?: return false
        val positionOpen = positions.any { it.ticket == positionTicket }
        val positionDeals = allDeals.filter { dealPositionKey(it) == positionTicket }
        val closingDeals = positionDeals.filter { it.entry != 0 }.sortedBy { it.timeMs }
        if (!positionOpen) {
            val closedQuantity = closingDeals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
            if (closedQuantity.compareTo(quantity) < 0) return false
        }
        if (positionOpen) {
            positionMetaByTicket[positionTicket] = PendingMeta(request.id, request.strategyId, protection)
            positionSymbolByTicket[positionTicket] = request.symbol
            positionOpenedAtByTicket[positionTicket] = openingDeals.minOf { it.timeMs }
        }
        bus.publish(
            BrokerEvent.OrderAccepted(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        bus.publish(
            BrokerEvent.OrderFilled(
                clientOrderId = request.id,
                brokerOrderId = positionTicket.toString(),
                symbol = request.symbol,
                side = request.side,
                price = price,
                quantity = quantity,
                strategyId = request.strategyId,
                timestamp = clock.now(),
            ),
        )
        if (!positionOpen) {
            val venueCosts =
                venueCostLedger.book(
                    positionTicket,
                    positionDeals,
                    positionClosed = true,
                    nowMs = clock.now(),
                )
            closingDeals.forEachIndexed { index, deal ->
                bus.publish(
                    BrokerEvent.OrderFilled(
                        clientOrderId = request.id,
                        brokerOrderId = positionTicket.toString(),
                        symbol = request.symbol,
                        side = if (deal.type == 0) Side.BUY else Side.SELL,
                        price = deal.price,
                        quantity = deal.volume,
                        strategyId = request.strategyId,
                        timestamp = clock.now(),
                        updatesOrderExecution = false,
                        venueCosts = if (index == closingDeals.lastIndex) venueCosts else BigDecimal.ZERO,
                        exitReason = closingDealExitReason(listOf(deal)),
                    ),
                )
            }
        }
        log.info(
            "MT5Broker {} order {} resolved from deal history as {} ticket {}",
            profile.name,
            request.id,
            if (positionOpen) "FILLED" else "FILLED_AND_CLOSED",
            positionTicket,
        )
        return true
    }

    private fun weightedDealPrice(deals: List<MT5Deal>): BigDecimal? {
        val quantity = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.volume }
        if (quantity.signum() <= 0) return null
        val notional = deals.fold(BigDecimal.ZERO) { total, deal -> total + deal.price.multiply(deal.volume) }
        return notional.divide(quantity, com.qkt.common.Money.CONTEXT)
    }

    private fun matchesUnknownPending(
        candidate: MT5PendingOrder,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean =
        candidate.type.equals(placement.type, ignoreCase = true) &&
            candidate.volume.compareTo(placement.volume) == 0 &&
            (placement.price == null || candidate.priceOpen.compareTo(placement.price) == 0) &&
            isNearPlacement(candidate.timeSetup, placementStartedAtMs)

    private fun matchesUnknownPosition(
        candidate: MT5Position,
        placement: MT5OrderRequest,
        placementStartedAtMs: Long,
    ): Boolean {
        val expectedType = if (placement.type.startsWith("BUY")) 0 else 1
        return candidate.type == expectedType &&
            candidate.volume.compareTo(placement.volume) == 0 &&
            isNearPlacement(candidate.openTime, placementStartedAtMs)
    }

    private fun isNearPlacement(
        venueEpoch: Long,
        placementStartedAtMs: Long,
    ): Boolean {
        if (venueEpoch <= 0L) return false
        val venueEpochMs = if (venueEpoch < 100_000_000_000L) venueEpoch * 1_000L else venueEpoch
        return abs(venueEpochMs - placementStartedAtMs) <= UNKNOWN_CORRELATION_WINDOW_MS
    }

    private sealed interface UnknownVenueMatch {
        data class Pending(
            val order: MT5PendingOrder,
        ) : UnknownVenueMatch

        data class Position(
            val position: MT5Position,
        ) : UnknownVenueMatch
    }

    /**
     * The venue stores only a truncated prefix (~16 chars) of the submitted comment, so
     * match in both directions: stored is a prefix of the wire comment, or vice versa.
     */
    private fun matchesComment(
        stored: String?,
        wireComment: String,
    ): Boolean {
        if (stored.isNullOrBlank()) return false
        return wireComment.startsWith(stored) || stored.startsWith(wireComment)
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
        for (preparedWire in prepared) {
            val wire = preparedWire.withPlacementId()
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
                registerPendingTicket(ticket, PendingMeta(legOrderId, request.strategyId))
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

    private fun MT5OrderRequest.withPlacementId(): MT5OrderRequest = copy(clientOrderId = placementIds.next())

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
        // A failed read must surface, not read as flat — a session that believes it is
        // flat while holding leveraged positions re-enters and doubles up (#376).
        val positions =
            client.getPositions(magic = profile.magic)
                ?: error(
                    "MT5Broker ${profile.name} getOpenPositions: gateway read failed" +
                        client.lastReadFailure()?.let { " ($it)" }.orEmpty(),
                )
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
        val snapshot =
            readMT5RecoverySnapshot(
                attempts = recoveryReadAttempts,
                backoffMs = recoveryReadBackoffMs,
                onFailedAttempt = { attempt, reason ->
                    log.warn(
                        "MT5Broker {} recovery read failed (attempt {}/{}): {}",
                        profile.name,
                        attempt,
                        recoveryReadAttempts,
                        reason,
                    )
                },
                readPendingOrders = { client.getPendingOrders(magic = profile.magic) },
                readPositions = { client.getPositions(magic = profile.magic) },
            )
        val pending = snapshot.pendingOrders
        val positions = snapshot.positions
        val recoveredPartialIds = recoverPartialEntries(orders, pending, positions)
        val resolvedOrders =
            orders.filterNot { it.id in recoveredPartialIds }.map { order ->
                if (order.brokerOrderId != null) return@map order
                val pendingMatch =
                    pending.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesComment(it.comment, order.id)
                    }
                val positionMatch =
                    positions.firstOrNull {
                        it.clientOrderId == order.id ||
                            matchesComment(it.comment, order.id)
                    }
                val ticket = pendingMatch?.ticket ?: positionMatch?.ticket
                if (ticket == null) {
                    order
                } else {
                    order.copy(brokerOrderId = ticket.toString())
                }
            }
        val actions = classifyOcoRecovery(resolvedOrders, pending.map { it.ticket }.toSet(), positions)
        // Pass 1: re-seed every still-pending leg before any fill is emitted, so a
        // cancel triggered by pass 2 can resolve its sibling's ticket.
        for (a in actions) {
            if (a is OcoRecoveryAction.Reseed) {
                registerPendingTicket(
                    a.ticket,
                    PendingMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        protectionFor(a.order.request),
                    ),
                )
                log.info(
                    "MT5Broker ${profile.name} recovery: re-seeded pending leg ${a.order.id} ticket=${a.ticket}",
                )
            }
            if (a is OcoRecoveryAction.TrackVanished) {
                registerPendingTicket(
                    a.ticket,
                    PendingMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        protectionFor(a.order.request),
                    ),
                )
            }
        }
        val vanishedTickets =
            actions
                .filterIsInstance<OcoRecoveryAction.TrackVanished>()
                .mapTo(mutableSetOf()) { it.ticket }
        pendingPoller.seedTrackedTickets(vanishedTickets)
        // Pass 2: republish the fill for any leg that filled while the daemon was down;
        // OrderManager's cancel-on-fill then unwinds the still-pending sibling.
        for (a in actions) {
            if (a is OcoRecoveryAction.EmitFill) {
                pendingByTicket[a.position.ticket] =
                    PendingMeta(
                        a.order.id,
                        a.order.request.strategyId,
                        protectionFor(a.order.request),
                    )
                log.info(
                    "MT5Broker ${profile.name} recovery: leg ${a.order.id} filled during downtime " +
                        "ticket=${a.position.ticket}",
                )
                onPendingPositionOpened(a.position)
            }
        }
    }

    private fun recoverPartialEntries(
        orders: List<com.qkt.execution.ManagedOrder>,
        pending: List<MT5PendingOrder>,
        positions: List<MT5Position>,
    ): Set<String> {
        val recovered = mutableSetOf<String>()
        for (order in orders) {
            val pendingMatches =
                pending.filter {
                    it.clientOrderId == order.id || matchesComment(it.comment, order.id)
                }
            val positionMatches =
                positions.filter {
                    it.clientOrderId == order.id || matchesComment(it.comment, order.id)
                }
            if (pendingMatches.size > 1 || positionMatches.size != 1) continue
            val position = positionMatches.single()
            val requestedQuantity = order.request.quantity
            if (position.volume.signum() <= 0 || position.volume >= requestedQuantity) continue

            val meta =
                PendingMeta(
                    order.id,
                    order.request.strategyId,
                    protectionFor(order.request),
                )
            positionMetaByTicket[position.ticket] = meta
            positionSymbolByTicket[position.ticket] = order.request.symbol
            positionOpenedAtByTicket[position.ticket] = position.openTime
            val partialEvent =
                BrokerEvent.OrderPartiallyFilled(
                    clientOrderId = order.id,
                    brokerOrderId = position.ticket.toString(),
                    symbol = order.request.symbol,
                    side = order.request.side,
                    price = position.priceOpen,
                    quantity = position.volume,
                    cumulativeFilled = position.volume,
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                )
            val residual = pendingMatches.singleOrNull()
            bus.publish(
                BrokerEvent.OrderAccepted(
                    clientOrderId = order.id,
                    brokerOrderId = (residual?.ticket ?: position.ticket).toString(),
                    strategyId = order.request.strategyId,
                    timestamp = clock.now(),
                ),
            )
            if (residual != null) {
                registerPartialEntry(
                    PartialEntryState(
                        meta = meta,
                        residualTicket = residual.ticket,
                        positionTicket = position.ticket,
                        symbol = order.request.symbol,
                        side = order.request.side,
                        requestedQuantity = requestedQuantity,
                        cumulativeFilled = position.volume,
                        averageFillPrice = position.priceOpen,
                    ),
                    openedAtMs = position.openTime,
                )
                bus.publish(partialEvent)
                pendingPoller.seedTrackedTickets(setOf(residual.ticket))
                log.info(
                    "MT5Broker {} recovery: restored partial entry {} residual={} position={} cumulative={}",
                    profile.name,
                    order.id,
                    residual.ticket,
                    position.ticket,
                    position.volume,
                )
            } else {
                bus.publish(partialEvent)
                bus.publish(
                    BrokerEvent.OrderCancelled(
                        clientOrderId = order.id,
                        brokerOrderId = position.ticket.toString(),
                        reason = "residual absent during partial-entry recovery",
                        strategyId = order.request.strategyId,
                        timestamp = clock.now(),
                    ),
                )
            }
            recovered += order.id
        }
        return recovered
    }

    override fun cancel(orderId: String) {
        val ticket = pendingTickets[orderId] ?: return
        val meta = pendingByTicket[ticket] ?: return
        // Non-blocking: OCO sibling-cancels and the halt kill-switch sweep call this from the
        // engine thread, and serialized round-trips stall it exactly when it must stop fast.
        // Keep both ticket maps until the venue confirms success. A rejected or ambiguous cancel
        // can race a fill; retaining the metadata lets the position poller attribute that fill.
        client.cancelOrderAsync(ticket) { response ->
            if (!isOrderSuccessful(response.result.retcode)) {
                log.warn(
                    "MT5Broker {} cancel({}, ticket={}) remains unresolved: {}",
                    profile.name,
                    orderId,
                    ticket,
                    response.errorMessage ?: "retcode=${response.result.retcode}",
                )
                bus.publish(
                    BrokerEvent.OrderCancelFailed(
                        clientOrderId = orderId,
                        brokerOrderId = ticket.toString(),
                        reason = response.errorMessage ?: "retcode=${response.result.retcode}",
                        strategyId = meta.strategyId,
                        timestamp = clock.now(),
                    ),
                )
                return@cancelOrderAsync
            }
            val cancelled =
                synchronized(pendingTransitionLock) {
                    if (pendingByTicket[ticket] != meta || pendingTickets[orderId] != ticket) {
                        false
                    } else {
                        pendingByTicket.remove(ticket)
                        pendingTickets.remove(orderId)
                        removePartialEntryByResidualTicket(ticket)
                        true
                    }
                }
            if (!cancelled) return@cancelOrderAsync
            bus.publish(
                BrokerEvent.OrderCancelled(
                    clientOrderId = orderId,
                    brokerOrderId = ticket.toString(),
                    reason = "user cancel",
                    strategyId = meta.strategyId,
                    timestamp = clock.now(),
                ),
            )
        }
    }

    override fun modifyPosition(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck {
        val t = ticket.toLongOrNull() ?: return positionModifyAck(ticket, false, "modifyPosition: bad ticket $ticket")
        positionModifyPreflightRejection(ticket, t, sl, tp)?.let { return it }
        val response =
            runCatching { client.modifyPosition(t, sl, tp) }
                .getOrElse { ex -> return positionModifyAck(ticket, false, ex.message) }
        return handlePositionModifyResult(ticket, t, sl, tp, response)
    }

    override fun modifyPositionAsync(
        ticket: String,
        sl: BigDecimal?,
        tp: BigDecimal?,
        onResult: (SubmitAck) -> Unit,
    ) {
        val t = ticket.toLongOrNull()
        if (t == null) {
            onResult(positionModifyAck(ticket, false, "modifyPosition: bad ticket $ticket"))
            return
        }
        positionModifyPreflightRejection(ticket, t, sl, tp)?.let {
            onResult(it)
            return
        }
        client.modifyPositionAsync(t, sl, tp) { response ->
            onResult(handlePositionModifyResult(ticket, t, sl, tp, response))
        }
    }

    private fun positionModifyPreflightRejection(
        ticket: String,
        t: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
    ): SubmitAck? {
        val qktSymbol = positionSymbolByTicket[t]
        if (qktSymbol != null) {
            val brokerSymbol = mt5Symbol.toBroker(qktSymbol.substringAfter(':'))
            val info = symbolMeta[brokerSymbol]
            val current = priceTracker?.lastPrice(qktSymbol)
            if (info != null && current != null && info.tradeFreezeLevel > 0) {
                val minDistance = info.point.multiply(BigDecimal(info.tradeFreezeLevel))
                val blocked =
                    listOfNotNull(sl, tp).firstOrNull { level ->
                        (level - current).abs() < minDistance
                    }
                if (blocked != null) {
                    return positionModifyAck(
                        ticket,
                        accepted = false,
                        reason =
                            "modify inside tradeFreezeLevel for $qktSymbol: " +
                                "level=$blocked current=$current minDistance=$minDistance",
                    )
                }
            }
        }
        return null
    }

    private fun handlePositionModifyResult(
        ticket: String,
        t: Long,
        sl: BigDecimal?,
        tp: BigDecimal?,
        response: MT5OrderResponse,
    ): SubmitAck {
        val ok = isOrderSuccessful(response.result.retcode)
        if (!ok) {
            log.warn(
                "MT5Broker {} modifyPosition({}) rejected: {}",
                profile.name,
                ticket,
                response.errorMessage ?: response.result.retcode,
            )
        }
        if (ok) {
            val change = PositionProtection(sl, tp)
            expectedProtectionByTicket[t] = change
            positionMetaByTicket.computeIfPresent(t) { _, meta ->
                val current = meta.protection
                meta.copy(
                    protection =
                        PositionProtection(
                            stopLoss = sl ?: current?.stopLoss,
                            takeProfit = tp ?: current?.takeProfit,
                        ),
                )
            }
        }
        return positionModifyAck(ticket, ok, if (ok) null else response.errorMessage)
    }

    private fun positionModifyAck(
        ticket: String,
        accepted: Boolean,
        reason: String? = null,
    ): SubmitAck = SubmitAck(clientOrderId = ticket, brokerOrderId = ticket, accepted = accepted, rejectReason = reason)

    private fun protectionFor(request: OrderRequest): PositionProtection? =
        runCatching { translator.translate(request) }
            .getOrNull()
            ?.let { translation ->
                (translation as? MT5Translation.Single)?.request?.let(::protectionOf)
            }

    private fun protectionOf(request: MT5OrderRequest): PositionProtection? =
        if (request.sl == null && request.tp == null) {
            null
        } else {
            PositionProtection(request.sl, request.tp)
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
                changes = changes,
                strategyId = pendingByTicket[ticket]?.strategyId ?: "",
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
     * is a separate concern. A ticket in [partialEntryByPositionTicket] remains working until its
     * cumulative position volume reaches the requested quantity or the residual disappears.
     */
    private fun onPendingPositionOpened(position: MT5Position): Boolean {
        if (reconcilePartialEntry(position)) return true
        val meta =
            synchronized(pendingTransitionLock) {
                pendingByTicket.remove(position.ticket)
                    ?: run {
                        if (!positionMetaByTicket.containsKey(position.ticket)) {
                            earlyPositionByTicket[position.ticket] = position
                        }
                        null
                    }
            }
        if (meta == null) {
            // Already tracked? The Fix A cross-check in onPendingDisappeared may have
            // synthesized this fill on a prior pending-poller tick; the position-poller
            // is now seeing the same ticket in its opened-delta. Silent — already done.
            if (positionMetaByTicket.containsKey(position.ticket)) return true
            log.warn(
                "MT5Broker {} saw new position ticket={} symbol={} side={} magic={} with no qkt-side " +
                    "pending meta yet; deferring attribution while awaiting a possible asynchronous " +
                    "placement response",
                profile.name,
                position.ticket,
                position.symbol,
                if (position.type == 0) "BUY" else "SELL",
                profile.magic,
            )
            return false
        }
        publishPendingPositionOpened(position, meta)
        return true
    }

    private fun onPositionIncreased(
        previous: MT5Position,
        latest: MT5Position,
    ) {
        if (latest.volume <= previous.volume) return
        reconcilePartialEntry(latest)
    }

    /**
     * Advances a partially filled entry from the position poller's cumulative venue volume.
     * Returns true when [position] belongs to a partial entry, including a duplicate snapshot.
     */
    private fun reconcilePartialEntry(position: MT5Position): Boolean {
        var event: BrokerEvent? = null
        synchronized(pendingTransitionLock) {
            val state = partialEntryByPositionTicket[position.ticket] ?: return false
            val venueCumulative = position.volume.min(state.requestedQuantity)
            if (venueCumulative <= state.cumulativeFilled) return true

            val sliceQuantity = venueCumulative - state.cumulativeFilled
            val slicePrice = incrementalEntryPrice(state, venueCumulative, position.priceOpen, sliceQuantity)
            positionOpenedAtByTicket[position.ticket] = position.openTime
            if (venueCumulative >= state.requestedQuantity) {
                partialEntryByPositionTicket.remove(position.ticket)
                partialPositionByResidualTicket.remove(state.residualTicket, position.ticket)
                pendingByTicket.remove(state.residualTicket, state.meta)
                pendingTickets.remove(state.meta.orderId, state.residualTicket)
                recentlyFilledTickets[state.residualTicket] = clock.now()
                event =
                    BrokerEvent.OrderFilled(
                        clientOrderId = state.meta.orderId,
                        brokerOrderId = position.ticket.toString(),
                        symbol = state.symbol,
                        side = state.side,
                        price = slicePrice,
                        quantity = sliceQuantity,
                        strategyId = state.meta.strategyId,
                        timestamp = clock.now(),
                    )
            } else {
                partialEntryByPositionTicket[position.ticket] =
                    state.copy(
                        cumulativeFilled = venueCumulative,
                        averageFillPrice = position.priceOpen,
                    )
                event =
                    BrokerEvent.OrderPartiallyFilled(
                        clientOrderId = state.meta.orderId,
                        brokerOrderId = position.ticket.toString(),
                        symbol = state.symbol,
                        side = state.side,
                        price = slicePrice,
                        quantity = sliceQuantity,
                        cumulativeFilled = venueCumulative,
                        strategyId = state.meta.strategyId,
                        timestamp = clock.now(),
                    )
            }
        }
        event?.let(bus::publish)
        return true
    }

    private fun incrementalEntryPrice(
        state: PartialEntryState,
        venueCumulative: BigDecimal,
        venueAveragePrice: BigDecimal,
        sliceQuantity: BigDecimal,
    ): BigDecimal {
        val venueNotional = venueAveragePrice.multiply(venueCumulative)
        val priorNotional = state.averageFillPrice.multiply(state.cumulativeFilled)
        val slicePrice = venueNotional.subtract(priorNotional).divide(sliceQuantity, com.qkt.common.Money.CONTEXT)
        return slicePrice.takeIf { it.signum() > 0 } ?: venueAveragePrice
    }

    private fun registerPendingTicket(
        ticket: Long,
        meta: PendingMeta,
    ) {
        val earlyPosition =
            synchronized(pendingTransitionLock) {
                pendingTickets[meta.orderId] = ticket
                pendingByTicket[ticket] = meta
                earlyPositionByTicket.remove(ticket)
            }
        if (earlyPosition != null) {
            onPendingPositionOpened(earlyPosition)
        }
    }

    private fun publishPendingPositionOpened(
        position: MT5Position,
        meta: PendingMeta,
    ) {
        pendingTickets.remove(meta.orderId)
        // Mark the ticket as recently filled so the pending-order poller doesn't
        // mistake the subsequent "disappeared from /orders" for an external cancel.
        recentlyFilledTickets[position.ticket] = clock.now()
        // Sweep stale entries on this always-firing position path too: the matching sweep in
        // onPendingDisappeared only runs when the gateway exposes an /orders endpoint, so without
        // this a gateway lacking /orders would let recentlyFilledTickets grow unbounded.
        recentlyFilledTickets.entries.removeIf {
            clock.now() - it.value >= profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
        }
        // Keep the meta accessible to the position poller for the eventual close event.
        positionMetaByTicket[position.ticket] = meta
        positionOpenedAtByTicket[position.ticket] = position.openTime
        val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(position.symbol)}"
        positionSymbolByTicket[position.ticket] = qktSymbol
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
     * to resolve which qkt strategy and clientOrderId originally opened it. Attribution
     * remains until full closure so multiple partial closes keep the same strategy id.
     */
    private fun lookupClosedTicketMeta(ticket: Long): ClosedPositionMeta? {
        val meta = positionMetaByTicket[ticket] ?: return null
        return ClosedPositionMeta(clientOrderId = meta.orderId, strategyId = meta.strategyId)
    }

    private fun removeClosedTicketMeta(ticket: Long) {
        partialEntryByPositionTicket[ticket]?.let { cancel(it.meta.orderId) }
        earlyPositionByTicket.remove(ticket)
        positionMetaByTicket.remove(ticket)
        positionSymbolByTicket.remove(ticket)
        positionOpenedAtByTicket.remove(ticket)
        expectedProtectionByTicket.remove(ticket)
    }

    private fun isExpectedProtectionChange(event: BrokerEvent.PositionProtectionChanged): Boolean {
        val ticket = event.ticket.toLongOrNull() ?: return false
        val expected = expectedProtectionByTicket[ticket] ?: return false
        val stopChanged = event.oldStopLoss.compareTo(event.newStopLoss) != 0
        val takeProfitChanged = event.oldTakeProfit.compareTo(event.newTakeProfit) != 0
        val stopMatches = !stopChanged || expected.stopLoss?.compareTo(event.newStopLoss) == 0
        val takeProfitMatches =
            !takeProfitChanged || expected.takeProfit?.compareTo(event.newTakeProfit) == 0
        if (!stopMatches || !takeProfitMatches) return false
        expectedProtectionByTicket.remove(ticket, expected)
        return true
    }

    /**
     * Returns the close request state for [ticket]. A pending marker is never consumed: if
     * the venue closes first and our request later rejects, the next poll must still publish
     * that venue close. A confirmed marker remains until its bounded TTL so a poll racing between
     * confirmation and fill publication cannot consume the only duplicate-suppression record.
     */
    private fun engineCloseState(ticket: Long): EngineCloseState {
        val now = clock.now()
        val ttlMs =
            maxOf(
                profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER,
                profile.httpTimeoutMs + profile.pollIntervalMs,
            )
        recentlyClosedByTicket.entries.removeIf {
            val confirmedAtMs = it.value.confirmedAtMs
            confirmedAtMs != null && now - confirmedAtMs >= ttlMs
        }
        val marker = recentlyClosedByTicket[ticket] ?: return EngineCloseState.NONE
        if (!marker.confirmed) return EngineCloseState.PENDING
        return EngineCloseState.CONFIRMED
    }

    private fun confirmEngineClose(ticket: Long) {
        recentlyClosedByTicket.computeIfPresent(ticket) { _, marker ->
            marker.copy(confirmed = true, confirmedAtMs = clock.now())
        }
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
    private fun onPendingDisappeared(ticket: Long): Boolean {
        val meta = pendingByTicket[ticket] ?: return true

        val ttlMs = profile.pollIntervalMs * DISAMBIGUATION_TTL_MULTIPLIER
        val recentlyFilledAt = recentlyFilledTickets[ticket]
        val now = clock.now()
        if (recentlyFilledAt != null && now - recentlyFilledAt < ttlMs) {
            pendingByTicket.remove(ticket)
            pendingTickets.entries.removeIf { it.value == ticket }
            recentlyFilledTickets.remove(ticket)
            return true
        }

        // Cross-check /positions before treating as cancel. If the ticket is now a
        // position, the pending-poller observed the transition before the position-poller
        // did — synthesize the fill path here instead of phantom-cancelling. A FAILED
        // read leaves fill-vs-cancel unresolved: keep the order tracked and let the next
        // poll cycle re-resolve rather than phantom-cancelling a possibly-filled leg.
        val positionsNow =
            client.getPositions(magic = profile.magic) ?: run {
                log.warn(
                    "MT5Broker ${profile.name} pending {} disappeared but /positions read failed — " +
                        "deferring fill-vs-cancel resolution",
                    ticket,
                )
                return false
            }
        val partialPositionTicket = partialPositionByResidualTicket[ticket]
        val asPosition =
            positionsNow.firstOrNull {
                it.ticket == (partialPositionTicket ?: ticket)
            }
        if (asPosition != null) {
            if (partialPositionByResidualTicket.containsKey(ticket)) {
                reconcilePartialEntry(asPosition)
                cancelPartialEntryResidual(
                    ticket,
                    "residual disappeared from venue after partial fill",
                )
                return true
            }
            onPendingPositionOpened(asPosition)
            return true
        }

        if (partialPositionByResidualTicket.containsKey(ticket)) {
            cancelPartialEntryResidual(
                ticket,
                "residual disappeared from venue after partial fill",
            )
            return true
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
        return true
    }

    private fun cancelPartialEntryResidual(
        ticket: Long,
        reason: String,
    ) {
        val state =
            synchronized(pendingTransitionLock) {
                val positionTicket = partialPositionByResidualTicket.remove(ticket) ?: return
                val current = partialEntryByPositionTicket.remove(positionTicket) ?: return
                pendingByTicket.remove(ticket, current.meta)
                pendingTickets.remove(current.meta.orderId, ticket)
                current
            }
        bus.publish(
            BrokerEvent.OrderCancelled(
                clientOrderId = state.meta.orderId,
                brokerOrderId = ticket.toString(),
                reason = reason,
                strategyId = state.meta.strategyId,
                timestamp = clock.now(),
            ),
        )
    }

    private fun removePartialEntryByResidualTicket(residualTicket: Long) {
        val positionTicket = partialPositionByResidualTicket.remove(residualTicket) ?: return
        partialEntryByPositionTicket.remove(positionTicket)
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
            volumeMax = info.volumeMax,
            pointSize = info.point,
            digits = info.digits,
            tradeStopsLevelPoints = info.tradeStopsLevel,
        )
    }

    override fun shutdown() {
        poller.stop()
        pendingPoller.stop()
        unknownResolveExecutor.shutdownNow()
    }

    companion object {
        /**
         * Multiplier applied to [MT5BrokerProfile.pollIntervalMs] for the
         * fill-vs-cancel disambiguation TTL. 3 cycles is enough headroom for the
         * position poller to tick at least once after the pending poller does.
         */
        private const val DISAMBIGUATION_TTL_MULTIPLIER: Long = 3L

        /** Venue queries before giving up on resolving an UNKNOWN send outcome. */
        private const val UNKNOWN_RESOLVE_ATTEMPTS: Int = 4
        private const val UNKNOWN_PERIODIC_RESOLVE_MIN_MS: Long = 5_000L

        /** Maximum distance from placement time for legacy comment-based correlation. */
        private const val UNKNOWN_CORRELATION_WINDOW_MS: Long = 60_000L
        private const val CLOSE_DEAL_CLOCK_SKEW_MS: Long = 1_000L

        /** Margin-level cache TTL — fresh enough for a floor check, cheap on the gateway. */
        private const val MARGIN_CACHE_TTL_MS: Long = 5_000L

        /** Deal-history window around an immediate fill used to retrieve its exact venue costs. */
        private const val DEAL_LOOKUP_WINDOW_MS: Long = 24L * 60L * 60L * 1_000L
    }
}
