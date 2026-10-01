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
 * A VGP v1 gateway in a test, built from the wire spec over a [FakeVenue]: idempotent submits, lookups
 * by id (an id answered `404` is refused for ever), deal and settlement windows, a kill switch gating
 * every order that is not `reduce_only`, a stream that replays its log after `since`, and [quotes].
 * Tests drive it with [venue], [reset], [killed], [unreachable], [failing], [login] and [codes].
 */
internal class FakeGateway(
    @Volatile var codes: List<String>,
) {
    private val json = Json { encodeDefaults = true }
    private val server = MockWebServer()
    private val log = CopyOnWriteArrayList<String>()
    private val sockets = CopyOnWriteArrayList<WebSocket>()
    private val dead = HashSet<String>()

    /** Closed bars by venue code and window; `GET /v1/bars` serves them [barsPage] at a time. */
    val bars = HashMap<Pair<String, Long>, List<WireBar>>()
    var barsPage = 2

    /** The quotes socket. */
    val quotes = FakeQuotes()

    /** The venue; change it through [act] so events stay in order. */
    val venue = FakeVenue { type, data -> emit(type, FakeWire.encode(data)) }

    @Volatile var killed = false

    /** While true, venue changes reach no stream and no log: events lost beyond the gateway's retention. */
    @Volatile var quiet = false

    /** Answers `503` to this many submits before serving one. */
    @Volatile var unreachable = 0

    /** Answers `500` to this many requests on each path before serving one. */
    val failing = HashMap<String, Int>()

    /** The account login `/v1/health` reports. */
    @Volatile var login = "7"

    /** Every submit body the gateway placed, in order. */
    val submits = CopyOnWriteArrayList<WireSubmit>()

    /** How many clients have the stream open. */
    val streams: Int get() = sockets.size

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
        quotes.drop()
        server.shutdown()
    }

    /** Changes the venue under the gateway's lock. */
    fun act(change: FakeVenue.() -> Unit) = synchronized(this) { venue.change() }

    /** The gateway's log restarted: connected clients get a `reset` and must resynchronize. */
    @Synchronized
    fun reset() {
        val event = """{"stream":"s1","seq":${log.size},"type":"reset"}"""
        sockets.forEach { it.send(event) }
    }

    private fun serve(request: RecordedRequest): MockResponse {
        val url = requireNotNull(request.requestUrl)
        val path = url.encodedPath
        failing[path]?.takeIf { it > 0 }?.let {
            failing[path] = it - 1
            return FakeWire.error(500, "internal", "failing on purpose")
        }
        val from = url.queryParameter("from")?.toLong()
        val to = url.queryParameter("to")?.toLong()
        val inWindow = { t: Long -> (from == null || t >= from) && (to == null || t <= to) }
        val id = url.queryParameter("client_order_id")
        val symbol = url.queryParameter("symbol")
        return when {
            path == "/v1/health" -> FakeWire.ok(health())
            path == "/v1/account" -> FakeWire.ok(FakeWire.ACCOUNT)
            path == "/v1/instruments" ->
                FakeWire.ok(
                    json.encodeToString(ListSerializer(WireInstrument.serializer()), codes.map(FakeWire::instrument)),
                )
            path == "/v1/positions" ->
                FakeWire.ok(
                    json.encodeToString(WirePositions.serializer(), WirePositions("netting", venue.positions())),
                )
            path == "/v1/deals" -> {
                val deals = venue.deals.filter { if (id != null) it.clientOrderId == id else inWindow(it.time) }
                FakeWire.ok(json.encodeToString(WireDeals.serializer(), WireDeals(deals)))
            }
            path == "/v1/settlements" -> {
                val settled =
                    venue.settlements.filter { if (symbol != null) it.symbol == symbol else inWindow(it.time) }
                FakeWire.ok(json.encodeToString(WireSettlements.serializer(), WireSettlements(settled)))
            }
            path == "/v1/orders" && request.method == "GET" -> {
                val working = venue.orders.values.filter { it.status == "working" }
                FakeWire.ok(json.encodeToString(WireOrders.serializer(), WireOrders(working)))
            }
            path == "/v1/orders" -> submit(json.decodeFromString(WireSubmit.serializer(), request.body.readUtf8()))
            path.startsWith("/v1/orders/") -> byId(path.removePrefix("/v1/orders/"), request.method ?: "GET")
            path == "/v1/stream" -> stream(url.queryParameter("since")?.toLong())
            path == "/v1/quotes" -> quotes.upgrade(url)
            path == "/v1/bars" -> bars(url)
            else -> MockResponse().setResponseCode(404)
        }
    }

    private fun submit(body: WireSubmit): MockResponse {
        if (unreachable > 0) {
            unreachable--
            return FakeWire.error(503, "venue_unavailable", "venue link down")
        }
        if (body.clientOrderId in dead) return FakeWire.error(409, "conflict", "${body.clientOrderId} was written off")
        venue.orders[body.clientOrderId]?.let { return FakeWire.ok(json.encodeToString(WireOrder.serializer(), it)) }
        if (killed && !body.reduceOnly) return FakeWire.error(423, "kill_switch", "all symbols halted")
        submits += body
        return FakeWire.ok(json.encodeToString(WireOrder.serializer(), venue.place(body))).setResponseCode(201)
    }

    private fun byId(
        id: String,
        method: String,
    ): MockResponse {
        if (id !in venue.orders) {
            if (method == "GET") dead += id
            return FakeWire.error(404, "not_found", "no order $id")
        }
        val order = if (method == "DELETE") venue.cancel(id) else venue.orders.getValue(id)
        return FakeWire.ok(json.encodeToString(WireOrder.serializer(), order))
    }

    private fun bars(url: okhttp3.HttpUrl): MockResponse {
        val window = requireNotNull(url.queryParameter("window_ms")).toLong()
        val from = requireNotNull(url.queryParameter("from")).toLong()
        val to = requireNotNull(url.queryParameter("to")).toLong()
        val all =
            bars[requireNotNull(url.queryParameter("symbol")) to window].orEmpty().filter {
                it.start in
                    from until to
            }
        val page = all.take(barsPage)
        val next = all.getOrNull(barsPage)?.start
        return FakeWire.ok(json.encodeToString(WireBars.serializer(), WireBars(page, next)))
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
        if (quiet) return
        val event = """{"stream":"s1","seq":${log.size + 1},"type":"$type","time":$TIME,"data":$data}"""
        log += event
        sockets.forEach { it.send(event) }
    }

    private fun health() =
        """{"protocol":"vgp1","adapter":"fake","adapter_version":"1","account_login":"$login","trade_mode":"demo",""" +
            """"venue_connected":true,"kill_switch":{"all":$killed},"server_time":$TIME,"stream":"s1","seq":${log.size}}"""

    companion object {
        const val TIME = 1_790_835_377_133L
    }
}
