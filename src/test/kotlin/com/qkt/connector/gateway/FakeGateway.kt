package com.qkt.connector.gateway

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest

/**
 * A VGP v1 gateway in a test, built from the wire spec: idempotent submits, cancels, deals,
 * settlements, a kill switch gating every order that is not `reduce_only`, and a stream that replays
 * its log after `since`. Tests drive the venue side with [fill], [settle], [kill] and [unreachable].
 */
internal class FakeGateway(
    private val codes: List<String>,
) {
    private val json = Json { encodeDefaults = true }
    private val server = MockWebServer()
    private val orders = LinkedHashMap<String, WireOrder>()
    private val deals = CopyOnWriteArrayList<WireFill>()
    private val settlements = CopyOnWriteArrayList<WireSettlement>()
    private val log = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()

    @Volatile var killed = false

    /** Answers `503` to this many submits before serving one. */
    @Volatile var unreachable = 0

    /** Every submit body the gateway served, in order. */
    val submits = CopyOnWriteArrayList<WireSubmit>()

    /** The gateway's base URL. */
    val url: String get() = server.url("/").toString().trimEnd('/')

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse =
                    synchronized(this@FakeGateway) { serve(request) }
            }
        server.start()
    }

    fun shutdown() {
        sockets.forEach { runCatching { it.close(1001, "gateway stopping") } }
        server.shutdown()
    }

    /** The venue fills [quantity] of order [clientOrderId] at [price], as fill [fillId]. */
    @Synchronized
    fun fill(
        clientOrderId: String,
        fillId: String,
        quantity: String,
        price: String,
    ) {
        val order = orders.getValue(clientOrderId)
        val fill = WireFill(clientOrderId, order.venueOrderId, fillId, order.symbol, order.side, quantity, price, TIME)
        deals += fill
        emit("fill", json.encodeToJsonElement(WireFill.serializer(), fill))
    }

    /** The venue settles expiring [code] at [price]. */
    @Synchronized
    fun settle(
        code: String,
        price: String,
    ) {
        val settlement = WireSettlement(code, price, TIME)
        settlements += settlement
        emit("settlement", json.encodeToJsonElement(WireSettlement.serializer(), settlement))
    }

    /** The gateway's log restarted: connected clients get a `reset` and must resynchronize. */
    @Synchronized
    fun reset() {
        val event = """{"stream":"s1","seq":${log.size},"type":"reset"}"""
        sockets.forEach { it.send(event) }
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val path = request.path.orEmpty().substringBefore('?')
        return when {
            path == "/v1/health" -> ok(HEALTH.replace("\"seq\":0", "\"seq\":${log.size}"))
            path == "/v1/account" -> ok(ACCOUNT)
            path == "/v1/instruments" ->
                ok(
                    json.encodeToString(ListSerializer(WireInstrument.serializer()), codes.map(::instrument)),
                )
            path == "/v1/positions" -> ok("""{"accounting":"netting","positions":[]}""")
            path == "/v1/deals" -> ok(json.encodeToString(WireDeals.serializer(), WireDeals(deals.toList())))
            path == "/v1/settlements" ->
                ok(
                    json.encodeToString(WireSettlements.serializer(), WireSettlements(settlements.toList())),
                )
            path == "/v1/orders" && request.method == "GET" ->
                ok(
                    json.encodeToString(
                        WireOrders.serializer(),
                        WireOrders(orders.values.filter { it.status == "working" }),
                    ),
                )
            path == "/v1/orders" -> submit(json.decodeFromString(WireSubmit.serializer(), request.body.readUtf8()))
            path.startsWith("/v1/orders/") -> cancel(path.removePrefix("/v1/orders/"))
            path == "/v1/stream" -> stream(request.requestUrl?.queryParameter("since")?.toLong())
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun submit(body: WireSubmit): MockResponse {
        if (unreachable > 0) {
            unreachable--
            return error(503, "venue_unavailable", "venue link down")
        }
        orders[body.clientOrderId]?.let { return ok(json.encodeToString(WireOrder.serializer(), it)) }
        if (killed && !body.reduceOnly) return error(423, "kill_switch", "all symbols halted")
        submits += body
        val order =
            WireOrder(
                body.clientOrderId,
                "v-${orders.size + 1}",
                body.symbol,
                body.side,
                body.type,
                body.quantity,
                body.limitPrice,
                body.stopPrice,
                body.timeInForce,
                body.reduceOnly,
                "working",
                "0",
                null,
                null,
                TIME,
                TIME,
            )
        orders[order.clientOrderId] = order
        emit("order", json.encodeToJsonElement(WireOrder.serializer(), order))
        return ok(json.encodeToString(WireOrder.serializer(), order)).setResponseCode(201)
    }

    private fun cancel(id: String): MockResponse {
        val order = orders[id] ?: return error(404, "not_found", "no order $id")
        val ended = order.copy(status = "cancelled")
        orders[id] = ended
        emit("order", json.encodeToJsonElement(WireOrder.serializer(), ended))
        return ok(json.encodeToString(WireOrder.serializer(), ended))
    }

    private fun stream(since: Long?): MockResponse =
        MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    synchronized(this@FakeGateway) {
                        log.drop(since?.toInt() ?: log.size).forEach { webSocket.send(it) }
                        sockets += webSocket
                    }
                }
            },
        )

    private fun emit(
        type: String,
        data: JsonElement,
    ) {
        val event = """{"stream":"s1","seq":${log.size + 1},"type":"$type","time":$TIME,"data":$data}"""
        log += event
        sockets.forEach { it.send(event) }
    }

    private fun instrument(code: String) = WireInstrument(code, "option", "USDC", "1", "5", "0.01", "0.01")

    private fun ok(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun error(
        status: Int,
        code: String,
        message: String,
    ) = ok("""{"error":{"code":"$code","message":"$message"}}""").setResponseCode(status)

    companion object {
        const val TIME = 1_790_835_377_133L
        const val HEALTH =
            """{"protocol":"vgp1","adapter":"fake","adapter_version":"1","account_login":"7","trade_mode":"demo",""" +
                """"venue_connected":true,"kill_switch":{"all":false},"server_time":1790835377133,"stream":"s1","seq":0}"""
        const val ACCOUNT =
            """{"currency":"USDC","balance":"1000","equity":"1000","margin_used":"0","margin_available":"1000"}"""
    }
}
