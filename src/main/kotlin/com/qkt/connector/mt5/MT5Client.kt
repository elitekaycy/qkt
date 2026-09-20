package com.qkt.connector.mt5

import java.math.BigDecimal
import java.net.URLEncoder
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.Buffer
import org.slf4j.LoggerFactory

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
    private val log = LoggerFactory.getLogger(MT5Client::class.java)
    private val json = Json { ignoreUnknownKeys = true }
    private val lastReadFailureRef = AtomicReference<String?>(null)
    private val venueTime = MT5VenueTime(serverTimeZone)
    private val responses = MT5OrderResponses(json)
    private val snapshots = MT5SnapshotParser(json, venueTime)
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
            .apply {
                if (transportJournal != null) {
                    addInterceptor { chain ->
                        val request = chain.request()
                        val startedNs = monotonicNanos()
                        val response =
                            try {
                                chain.proceed(request)
                            } catch (error: java.io.IOException) {
                                recordTransportExchange(
                                    request = request,
                                    responseCode = null,
                                    responseBody = null,
                                    responseBytes = null,
                                    error = error.message ?: error.javaClass.simpleName,
                                    startedNs = startedNs,
                                )
                                throw error
                            }
                        // A successful snapshot read (account, positions, orders, deals) is
                        // re-polled every second and its body is venue state the engine already
                        // projects elsewhere; journaling it was ~99% of transport volume. Keep
                        // the exchange, drop the body. Mutations and failures keep everything.
                        val elideBody = request.method == "GET" && response.isSuccessful
                        recordTransportExchange(
                            request = request,
                            responseCode = response.code,
                            responseBody =
                                if (elideBody) {
                                    null
                                } else {
                                    runCatching { response.peekBody(MAX_CAPTURE_BODY_BYTES).string() }.getOrNull()
                                },
                            responseBytes = if (elideBody) response.body?.contentLength() else null,
                            error = null,
                            startedNs = startedNs,
                        )
                        response
                    }
                }
            }.build()

    private fun recordTransportExchange(
        request: okhttp3.Request,
        responseCode: Int?,
        responseBody: String?,
        responseBytes: Long?,
        error: String?,
        startedNs: Long,
    ) {
        runCatching {
            transportJournal?.record(
                method = request.method,
                path =
                    request.url.encodedPath +
                        request.url.encodedQuery
                            ?.let { "?$it" }
                            .orEmpty(),
                requestBody = captureRequestBody(request),
                responseCode = responseCode,
                responseBody = responseBody,
                responseBytes = responseBytes,
                error = error,
                durationMs = (monotonicNanos() - startedNs) / 1_000_000L,
                idempotencyKey = request.header("Idempotency-Key"),
                engineOrderId = request.tag(TransportCorrelation::class.java)?.engineOrderId,
            )
        }.onFailure { captureError ->
            log.error("MT5 transport capture failed without affecting request: {}", captureError.message)
        }
    }

    private fun captureRequestBody(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        if (body.isOneShot()) return "<one-shot body omitted>"
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val size = buffer.size.coerceAtMost(MAX_CAPTURE_BODY_BYTES)
            buffer.readUtf8(size)
        }.getOrNull()
    }

    fun isReady(): Boolean =
        runCatching {
            val resp = http.newCall(mt5RequestBuilder("$gatewayUrl/health/ready", apiKey).build()).execute()
            resp.use { response ->
                if (!response.isSuccessful) return@use false
                val payload = response.body?.string().orEmpty()
                val root = json.parseToJsonElement(payload).jsonObject
                root["status"]?.jsonPrimitive?.contentOrNull == "ready"
            }
        }.getOrDefault(false)

    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse {
        readCache?.clear()
        val body = orderJson(req).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/order", apiKey)
                .header("Idempotency-Key", req.clientOrderId)
                .tag(TransportCorrelation::class.java, TransportCorrelation(req.engineOrderId))
                .post(body)
                .build()
        // POST /order is NOT retried: duplicate placement is worse than a surfaced failure.
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    /**
     * Place an order WITHOUT blocking the caller. The HTTP send runs on OkHttp's dispatcher
     * (its own worker pool with a per-host cap); [onResult] is invoked on a dispatcher thread
     * with the parsed [MT5OrderResponse] on completion, or a synthetic failure response
     * (retcode -1, [MT5OrderResponse.errorMessage] set) on a non-2xx or IO error. Like
     * [placeOrder] the send is NOT retried — duplicate placement is worse than a surfaced
     * failure. This frees the engine thread from the order round-trip; the broker layer turns
     * [onResult] into the venue's `OrderAccepted`/`OrderRejected`/`OrderFilled` bus events.
     */
    fun placeOrderAsync(
        req: MT5OrderRequest,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val body = orderJson(req).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/order", apiKey)
                .header("Idempotency-Key", req.clientOrderId)
                .tag(TransportCorrelation::class.java, TransportCorrelation(req.engineOrderId))
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        onResult(responses.parseAsyncMutationResponse(it))
                    }
                }
            },
        )
    }

    /**
     * Fetch the venue's open positions. Returns `null` when the read FAILED (gateway
     * unreachable / non-2xx after retries) — callers must treat that as "unknown",
     * never as "no positions". An outage that reads as an empty account makes the
     * pollers synthesize a close for every open position (#359).
     */
    fun getPositions(magic: Int? = null): List<MT5Position>? {
        val filterLocally = magic != null && readCache != null
        val url =
            when {
                filterLocally -> "$gatewayUrl/get_positions"
                magic != null -> "$gatewayUrl/get_positions?magic=$magic"
                else -> "$gatewayUrl/get_positions"
            }
        val raw = getWithRetry(url) ?: return null
        val positions = snapshots.parsePositions(raw)
        return if (filterLocally) {
            positions.filter { it.magic == magic }
        } else {
            positions
        }
    }

    /**
     * Fetch the venue's working (pending) orders. Returns `null` when the read FAILED
     * (gateway unreachable, non-2xx after retries, or a gateway too old to expose
     * `/orders`) — callers must treat that as "unknown", never as "all cancelled".
     */
    fun getPendingOrders(magic: Int? = null): List<MT5PendingOrder>? {
        val filterLocally = magic != null && readCache != null
        val url =
            when {
                filterLocally -> "$gatewayUrl/orders"
                magic != null -> "$gatewayUrl/orders?magic=$magic"
                else -> "$gatewayUrl/orders"
            }
        val raw = getWithRetry(url) ?: return null
        val orders = snapshots.parsePendingOrders(raw)
        return if (filterLocally) {
            orders.filter { it.magic == magic }
        } else {
            orders
        }
    }

    /**
     * Fetch the venue's symbol metadata (volume step / min, digits, point, stops level).
     *
     * Returns `null` if the gateway doesn't expose the symbol or the call fails — the
     * caller decides whether to fall back to a configured override or pass-through.
     */
    fun getSymbolInfo(brokerSymbol: String): MT5SymbolInfo? {
        val raw = getWithRetry("$gatewayUrl/symbol_info/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5SymbolInfo(
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            digits = obj["digits"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            point = obj["point"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            tradeStopsLevel = obj["trade_stops_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            tradeFreezeLevel = obj["trade_freeze_level"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            volumeMin = obj["volume_min"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            volumeStep = obj["volume_step"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            volumeMax =
                obj["volume_max"]
                    ?.jsonPrimitive
                    ?.contentOrNull
                    ?.toBigDecimalOrNull()
                    ?.takeIf { it.signum() > 0 },
            // Default 1 keeps callers safe if the gateway version doesn't return the field;
            // for known instruments the venue always populates it (XAUUSD=100, EURUSD=100000).
            contractSize =
                obj["trade_contract_size"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull()
                    ?: BigDecimal.ONE,
        )
    }

    /**
     * Fetch the account snapshot via `GET /account`. The [MT5AccountInfo.marginMode] field
     * tells the broker whether the venue is netting (`0`) or hedging (`2`) — the routing
     * decision for closing a position. Returns `null` if the call fails.
     */
    fun getAccount(): MT5AccountInfo? {
        val raw = getWithRetry("$gatewayUrl/account") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        return MT5AccountInfo(
            balance = obj["balance"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            equity = obj["equity"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            currency = obj["currency"]?.jsonPrimitive?.contentOrNull ?: "",
            leverage = obj["leverage"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 0,
            marginMode = obj["margin_mode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: MARGIN_MODE_UNKNOWN,
            marginFree = obj["margin_free"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            marginLevel = obj["margin_level"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            margin = obj["margin"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            profit = obj["profit"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
            login = obj["login"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
            server = obj["server"]?.jsonPrimitive?.contentOrNull ?: "",
            name = obj["name"]?.jsonPrimitive?.contentOrNull ?: "",
            tradeMode = obj["trade_mode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
        )
    }

    /**
     * Close an open position by its venue ticket via `POST /close_position`, optionally a
     * partial [volume]. This is how a hedging account is reduced without opening a counter
     * position. The gateway wraps `order_send` underneath, so a success mirrors the
     * `POST /order` `{"result":{...}}` shape; a non-2xx (e.g. a bad ticket returns
     * `{"error":...}`) is captured in [MT5OrderResponse.errorMessage]. Not retried —
     * a duplicate close is worse than a surfaced failure.
     */
    fun closePosition(
        ticket: Long,
        volume: BigDecimal? = null,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = closePositionJson(ticket, volume).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/close_position", apiKey)
                .post(body)
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    /**
     * Close a position without blocking the caller.
     *
     * With [partial] set, [volume] is required and the request uses the gateway's dedicated
     * `/position_close_partial` route. Otherwise it uses `/close_position`, the async sibling
     * of [closePosition]. [onResult] runs on an OkHttp dispatcher thread with the parsed
     * response, or a synthetic retcode -1 failure. Not retried.
     * Closes ride the engine thread (CLOSE rules, trailing stops, flattens), where a
     * blocking gateway round-trip stalls tick processing exactly when exits matter.
     */
    fun closePositionAsync(
        ticket: Long,
        volume: BigDecimal? = null,
        partial: Boolean = false,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val path: String
        val payload: String
        if (partial) {
            val closeVolume = requireNotNull(volume) { "partial close requires volume" }
            path = "/position_close_partial"
            payload = partialCloseJson(ticket, closeVolume)
        } else {
            path = "/close_position"
            payload = closePositionJson(ticket, volume)
        }
        val body = payload.toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl$path", apiKey)
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        onResult(responses.parseAsyncMutationResponse(it))
                    }
                }
            },
        )
    }

    /**
     * Modify an OPEN position's SL/TP via `POST /modify_sl_tp` (gateway `TRADE_ACTION_SLTP`).
     * This is how a trailing stop keeps a venue-side stop in place — push the new SL level onto
     * the position so the broker still protects it even if qkt is offline. Returns the standard
     * `{"result":{...}}` envelope; a non-2xx is captured in [MT5OrderResponse.errorMessage].
     *
     * The gateway treats an omitted `sl`/`tp` as `0.0`, which *clears* that level. To avoid
     * clearing the take-profit when only trailing the stop, pass the current [tp] alongside [sl].
     * Not retried — a duplicate modify is harmless but a surfaced failure is preferable to silent
     * retries racing the trail.
     */
    fun modifyPosition(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = modifyPositionJson(ticket, sl, tp).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/modify_sl_tp", apiKey)
                .post(body)
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val normalized = responses.noChangesSuccess(ticket, raw)
                if (normalized != null) return normalized
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    /** Modify an open position's SL/TP on OkHttp's dispatcher without blocking the caller. */
    fun modifyPositionAsync(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val body = modifyPositionJson(ticket, sl, tp).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/modify_sl_tp", apiKey)
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        onResult(responses.parseAsyncModifyResponse(ticket, it))
                    }
                }
            },
        )
    }

    /**
     * The deals that closed venue position [positionTicket], via `GET /history_deals_get`:
     * the volume-weighted exit price plus the position's total venue costs (commission +
     * swap + fee across all its deals). This is the truth for a venue-side close (broker
     * SL/TP, manual close, stop-out) — the engine's last tick is a proxy that is stalest
     * exactly when venue-side closes happen.
     *
     * [fromUtcMs]/[toUtcMs] bound the search (the position's open time and now);
     * both are padded a day and shifted to venue time. Returns `null` when the
     * gateway can't be read or no closing deal exists in the window — callers fall
     * back to their best local proxy.
     */
    fun getClosingDeal(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): MT5ClosingDeal? {
        val deals = getPositionDeals(positionTicket, fromUtcMs, toUtcMs) ?: return null
        // DEAL_ENTRY_IN (0) opened the position; OUT (1) / INOUT (2) / OUT_BY (3)
        // reduced or closed it. The close may have happened in several partial deals —
        // volume-weight them into the single price the synthesized fill carries.
        var volume = BigDecimal.ZERO
        var notional = BigDecimal.ZERO
        var reported = BigDecimal.ZERO
        for (deal in deals) {
            reported = reported.add(deal.commission).add(deal.swap).add(deal.fee)
            if (deal.entry == 0 || deal.volume.signum() <= 0) continue
            volume = volume.add(deal.volume)
            notional = notional.add(deal.price.multiply(deal.volume))
        }
        if (volume.signum() == 0) return null
        return MT5ClosingDeal(
            price = notional.divide(volume, com.qkt.common.Money.CONTEXT),
            costs = reported.negate(),
            deals = deals,
        )
    }

    /**
     * All venue deals for [positionTicket] in the requested UTC range. The query is padded
     * by one day at each edge, matching [getClosingDeal], so entry-side costs are included.
     * The response is filtered locally because gateways may ignore the wire-level position
     * filter and return account-wide history.
     */
    fun getPositionDeals(
        positionTicket: Long,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? {
        val from = venueTime.venueIso(fromUtcMs - DEAL_WINDOW_PAD_MS)
        val to = venueTime.venueIso(toUtcMs + DEAL_WINDOW_PAD_MS)
        val url = "$gatewayUrl/history_deals_get?from_date=$from&to_date=$to&position=$positionTicket"
        val raw = getWithRetry(url) ?: return null
        val arr = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return arr
            .map { snapshots.parseDeal(it.jsonObject) }
            .filter { it.positionTicket == positionTicket }
    }

    /**
     * Every deal the venue booked in `[fromUtcMs, toUtcMs]` — all positions, all symbols
     * — via `GET /history_deals_get` with a date range only (no position filter). Powers
     * the insights deal-history backfill. Bounds are shifted to venue time on the wire;
     * deal times come back as UTC millis. Returns `null` when the read FAILED (gateway
     * unreachable / non-2xx after retries) — callers must treat that as "unknown",
     * never as "no deals", or an outage silently skips a slice of history.
     */
    fun getDeals(
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Deal>? {
        val url = "$gatewayUrl/history_deals_get?from_date=${venueTime.venueIso(
            fromUtcMs,
        )}&to_date=${venueTime.venueIso(toUtcMs)}"
        val raw = getWithRetry(url) ?: return null
        val arr = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return arr.map { snapshots.parseDeal(it.jsonObject) }
    }

    fun getTick(brokerSymbol: String): MT5Tick? {
        val raw = getWithRetry("$gatewayUrl/symbol_info_tick/$brokerSymbol") ?: return null
        val obj = json.parseToJsonElement(raw).jsonObject
        val rawTime = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
        val rawTimeMs = obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: rawTime * 1_000L
        val timeMs = venueTime.venueEpochToUtc(rawTimeMs)
        return MT5Tick(
            symbol = brokerSymbol,
            bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
            time = timeMs / 1_000L,
            timeMs = timeMs,
        )
    }

    /** Return raw venue ticks for the inclusive UTC millisecond window. */
    fun getTicksRange(
        brokerSymbol: String,
        fromUtcMs: Long,
        toUtcMs: Long,
    ): List<MT5Tick>? {
        require(toUtcMs >= fromUtcMs) { "MT5 tick-history range ends before it starts" }
        val from =
            URLEncoder.encode(
                Instant
                    .ofEpochMilli(fromUtcMs)
                    .toString(),
                Charsets.UTF_8,
            )
        val to =
            URLEncoder.encode(
                Instant
                    .ofEpochMilli(toUtcMs)
                    .toString(),
                Charsets.UTF_8,
            )
        val raw =
            getWithRetry(
                "$gatewayUrl/copy_ticks_range?symbol=$brokerSymbol&from_date=$from&to_date=$to",
            ) ?: return null
        val rows = unwrapMT5Data(json.parseToJsonElement(raw)) as? JsonArray ?: return null
        return rows.map { element ->
            val obj = element.jsonObject
            val time = obj["time"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L
            val timeMs =
                venueTime.venueEpochToUtc(
                    obj["time_msc"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: time * 1_000L,
                )
            MT5Tick(
                symbol = brokerSymbol,
                bid = obj["bid"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                ask = obj["ask"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                time = timeMs / 1_000L,
                timeMs = timeMs,
            )
        }
    }

    /**
     * Cancel a working order via `DELETE /orders/{ticket}`.
     *
     * Returns the raw response body on success, or an empty string on HTTP failure.
     * The caller logs a warning when the body indicates a non-success retcode so a
     * 404/4xx no longer masquerades as a successful cancel — the prior `POST /cancel/...`
     * shape silently succeeded against gateways that returned an HTML 404 page.
     */
    fun cancelOrder(ticket: Long): String {
        readCache?.clear()
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .delete()
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                log.warn("MT5Client cancelOrder($ticket) HTTP ${it.code}: $raw")
                return ""
            }
            return raw
        }
    }

    /**
     * Cancel a pending order without blocking the caller.
     *
     * [onResult] receives a parsed venue result. HTTP and I/O failures are represented by a
     * response with retcode `-1` and a populated [MT5OrderResponse.errorMessage], matching
     * [placeOrderAsync]. Legacy gateways that confirm cancellation with only a success message
     * are normalized to [MT5_TRADE_RETCODE_DONE].
     */
    fun cancelOrderAsync(
        ticket: Long,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .delete()
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    log.warn("MT5Client cancelOrder($ticket) IO error: ${e.message}")
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            log.warn("MT5Client cancelOrder($ticket) HTTP ${it.code}: $raw")
                            onResult(responses.errorResponse("HTTP ${it.code}: $raw"))
                        } else {
                            onResult(responses.parseCancelResponse(raw))
                        }
                    }
                }
            },
        )
    }

    /**
     * Modify a working order via `PUT /orders/{ticket}`. Returns the gateway's
     * [MT5OrderResponse] — successful when [MT5OrderResult.retcode] is
     * `MT5_TRADE_RETCODE_DONE`. A non-2xx response is captured in [MT5OrderResponse.errorMessage]
     * so the broker layer can reject deterministically.
     */
    fun modifyOrder(
        ticket: Long,
        mods: MT5OrderModification,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = orderModificationJson(mods).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .put(body)
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    private fun getWithRetry(url: String): String? {
        val cache = readCache
        return if (cache != null && isSnapshotRead(url)) {
            cache.get(url) { getFromNetworkWithRetry(url) }
        } else {
            getFromNetworkWithRetry(url)
        }
    }

    private fun isSnapshotRead(url: String): Boolean =
        when (url.substringAfter(gatewayUrl)) {
            "/account", "/get_positions", "/orders" -> true
            else ->
                url.startsWith("$gatewayUrl/get_positions?") ||
                    url.startsWith("$gatewayUrl/orders?")
        }

    private fun getFromNetworkWithRetry(url: String): String? {
        var attempt = 0
        var failure: String? = null
        while (attempt <= retryAttempts) {
            try {
                val resp = http.newCall(mt5RequestBuilder(url, apiKey).build()).execute()
                resp.use {
                    val raw = it.body?.string().orEmpty()
                    if (it.isSuccessful) {
                        lastReadFailureRef.set(null)
                        return raw
                    }
                    failure = "HTTP ${it.code}: $raw"
                }
            } catch (e: java.io.IOException) {
                failure = "IO error: ${e.message}"
            }
            attempt++
            if (attempt <= retryAttempts) Thread.sleep(200L * attempt)
        }
        lastReadFailureRef.set(failure ?: "gateway read failed")
        // Message-only: a refused/timed-out GET after retries is an expected operational
        // condition; the okhttp stack adds no signal and floods test output (#879).
        log.warn("MT5Client GET $url failed after $retryAttempts retries: {}", lastReadFailureRef.get())
        return null
    }

    /** Detail from the most recent failed GET, cleared by the next successful network read. */
    fun lastReadFailure(): String? = lastReadFailureRef.get()

    companion object {
        /** Epochs below this are seconds, not milliseconds (100_000_000_000 ms is 1973). */
        private const val EPOCH_MS_THRESHOLD = 100_000_000_000L

        private const val MAX_CAPTURE_BODY_BYTES = 64L * 1024L
        private val JSON_MEDIA = "application/json".toMediaType()

        /** Padding either side of the deal search window — venue clock skew is hours, not days. */
        private const val DEAL_WINDOW_PAD_MS: Long = 24L * 3600_000L
    }

    private data class TransportCorrelation(
        val engineOrderId: String,
    )
}
