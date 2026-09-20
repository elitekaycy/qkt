package com.qkt.connector.mt5

import java.math.BigDecimal
import java.time.Duration
import kotlinx.serialization.json.Json
import okhttp3.Dispatcher
import okhttp3.OkHttpClient

/**
 * Low-level HTTP client for the `mt5-gateway` service.
 *
 * Handles JSON serialization, retries on GETs (POST /order
 * is deliberately not retried — duplicate placement is worse than a surfaced failure),
 * broker-local query windows and UTC epoch timestamps returned by MT5, plus basic error parsing.
 * Daemon deployments may share one instance across strategy-local brokers; [readCache] collapses
 * their identical snapshot reads without sharing any broker attribution state.
 */
class MT5Client(
    private val gatewayUrl: String,
    private val serverTimeZone: MT5ServerTimeZone,
    private val httpTimeoutMs: Long = 5000,
    private val retryAttempts: Int = 3,
    private val apiKey: String? = null,
    private val readCache: MT5ReadCache? = null,
    private val transportJournal: MT5TransportJournal? = null,
    private val monotonicNanos: () -> Long = System::nanoTime,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val venueTime = MT5VenueTime(serverTimeZone)
    private val responses = MT5OrderResponses(json)
    private val snapshots = MT5SnapshotParser(json, venueTime)
    private val recorder = transportJournal?.let { MT5TransportRecorder(it, monotonicNanos) }
    private val dispatcher =
        Dispatcher().apply {
            maxRequestsPerHost = 16
        }

    private val http: OkHttpClient =
        OkHttpClient
            .Builder()
            .dispatcher(dispatcher)
            .callTimeout(Duration.ofMillis(httpTimeoutMs))
            .connectTimeout(Duration.ofMillis(httpTimeoutMs))
            .apply { recorder?.let { addInterceptor(it) } }
            .build()

    private val reads = MT5GatewayReads(http, gatewayUrl, apiKey, retryAttempts, readCache)
    private val placement = MT5OrderPlacement(http, gatewayUrl, apiKey, responses, readCache)
    private val workingOrders = MT5WorkingOrderCalls(http, gatewayUrl, apiKey, responses, readCache)
    private val positionCalls = MT5PositionCalls(http, gatewayUrl, apiKey, json, responses, readCache)
    private val accountReads = MT5AccountReads(http, gatewayUrl, apiKey, json, reads, snapshots, readCache)
    private val historyReads = MT5HistoryReads(gatewayUrl, json, reads, snapshots, venueTime)

    /** See [MT5AccountReads.isReady]. */
    fun isReady(): Boolean = accountReads.isReady()

    /** See [MT5OrderPlacement.placeOrder]. */
    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse = placement.placeOrder(req)

    /** See [MT5OrderPlacement.placeOrderAsync]. */
    fun placeOrderAsync(
        req: MT5OrderRequest,
        onResult: (MT5OrderResponse) -> Unit,
    ) = placement.placeOrderAsync(req, onResult)

    /** See [MT5AccountReads.getPositions]. */
    fun getPositions(magic: Int? = null): List<MT5Position>? = accountReads.getPositions(magic)

    /** See [MT5AccountReads.getPendingOrders]. */
    fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder>? = accountReads.getPendingOrders(magic)

    /** See [MT5AccountReads.getSymbolInfo]. */
    fun getSymbolInfo(brokerSymbol: String): MT5SymbolInfo? = accountReads.getSymbolInfo(brokerSymbol)

    /** See [MT5AccountReads.getAccount]. */
    fun getAccount(): MT5AccountInfo? = accountReads.getAccount()

    /** See [MT5PositionCalls.closePosition]. */
    fun closePosition(
        ticket: Long,
        volume: BigDecimal? = null,
    ): MT5OrderResponse = positionCalls.closePosition(ticket, volume)

    /** See [MT5PositionCalls.closePositionAsync]. */
    fun closePositionAsync(
        ticket: Long,
        volume: BigDecimal? = null,
        partial: Boolean = false,
        onResult: (MT5OrderResponse) -> Unit,
    ) = positionCalls.closePositionAsync(ticket, volume, partial, onResult)

    /** See [MT5PositionCalls.modifyPosition]. */
    fun modifyPosition(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
    ): MT5OrderResponse = positionCalls.modifyPosition(ticket, sl, tp)

    /** See [MT5PositionCalls.modifyPositionAsync]. */
    fun modifyPositionAsync(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
        onResult: (MT5OrderResponse) -> Unit,
    ) = positionCalls.modifyPositionAsync(ticket, sl, tp, onResult)

    /** See [MT5HistoryReads.getClosingDeal]. */
    fun getClosingDeal(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): MT5ClosingDeal? = historyReads.getClosingDeal(positionTicket, fromUtcMs, toUtcMs)

    /** See [MT5HistoryReads.getPositionDeals]. */
    fun getPositionDeals(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? = historyReads.getPositionDeals(positionTicket, fromUtcMs, toUtcMs)

    /** See [MT5HistoryReads.getDeals]. */
    fun getDeals(
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? = historyReads.getDeals(fromUtcMs, toUtcMs)

    /** See [MT5HistoryReads.getTick]. */
    fun getTick(brokerSymbol: String): MT5Tick? = historyReads.getTick(brokerSymbol)

    /** See [MT5HistoryReads.getTicksRange]. */
    fun getTicksRange(
        brokerSymbol: String,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Tick>? = historyReads.getTicksRange(brokerSymbol, fromUtcMs, toUtcMs)

    /** See [MT5WorkingOrderCalls.cancelOrder]. */
    fun cancelOrder(ticket: Long): String = workingOrders.cancelOrder(ticket)

    /** See [MT5WorkingOrderCalls.cancelOrderAsync]. */
    fun cancelOrderAsync(
        ticket: Long,
        onResult: (MT5OrderResponse) -> Unit,
    ) = workingOrders.cancelOrderAsync(ticket, onResult)

    /** See [MT5WorkingOrderCalls.modifyOrder]. */
    fun modifyOrder(
        ticket: Long,
        mods: MT5OrderModification,
    ): MT5OrderResponse = workingOrders.modifyOrder(ticket, mods)

    /** Detail from the most recent failed GET, cleared by the next successful network read. */
    fun lastReadFailure(): String? = reads.lastReadFailure()
}
