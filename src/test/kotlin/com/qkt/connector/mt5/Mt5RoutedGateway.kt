package com.qkt.connector.mt5

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy

/**
 * A fake mt5-gateway answering by path: each route holds a queue of replies, the last of which repeats.
 * A path with no route answers 404. Every request is recorded as `METHOD /path?query` plus its body.
 */
internal class Mt5RoutedGateway : AutoCloseable {
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, CopyOnWriteArrayList<MockResponse>>()
    val requests = CopyOnWriteArrayList<Pair<String, String>>()

    init {
        server.dispatcher =
            object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    val target = request.path ?: "/"
                    requests.add("${request.method} $target" to request.body.readUtf8())
                    val path = target.substringBefore('?')
                    val queue = routes[path] ?: return MockResponse().setResponseCode(404).setBody("{}")
                    return if (queue.size > 1) queue.removeAt(0) else queue.first()
                }
            }
        server.start()
    }

    val url: String get() = server.url("/").toString().trimEnd('/')

    /** Answers [path] with [bodies] in turn, repeating the last. */
    fun on(
        path: String,
        vararg bodies: String,
    ) {
        routes[path] = CopyOnWriteArrayList(bodies.map { MockResponse().setBody(it) })
    }

    /** Answers [path] with each (status, body) reply in turn, repeating the last. */
    fun respond(
        path: String,
        vararg replies: Pair<Int, String>,
    ) {
        routes[path] =
            CopyOnWriteArrayList(replies.map { (status, body) -> MockResponse().setResponseCode(status).setBody(body) })
    }

    /** Answers [path] with HTTP [status]. */
    fun fail(
        path: String,
        status: Int = 500,
    ) {
        routes[path] = CopyOnWriteArrayList(listOf(MockResponse().setResponseCode(status).setBody("{}")))
    }

    /** Drops every connection to [path] before answering, so the caller sees an IO failure. */
    fun drop(path: String) {
        val dropped = MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AFTER_REQUEST)
        routes[path] = CopyOnWriteArrayList(listOf(dropped))
    }

    /** The recorded request lines whose path starts with [prefix]. */
    fun calls(prefix: String): List<String> =
        requests.map { it.first }.filter { it.substringAfter(' ').startsWith(prefix) }

    override fun close() {
        server.shutdown()
    }

    companion object {
        /** One deal row as `/history_deals_get` reports it. */
        fun deal(
            ticket: Long,
            position: Long,
            entry: Int,
            volume: String,
            price: String,
            type: Int = 0,
            symbol: String = "XAUUSDm",
            magic: Int = 10001,
            comment: String = "",
            clientOrderId: String? = null,
            timeMs: Long = 0L,
            commission: String = "0",
            reason: Int? = null,
        ): String =
            """{"ticket":$ticket,"order":$ticket,"position_id":$position,"symbol":"$symbol","type":$type,""" +
                """"entry":$entry,"volume":"$volume","price":"$price","commission":"$commission",""" +
                """"magic":$magic,"comment":"$comment","time_msc":$timeMs""" +
                (clientOrderId?.let { ""","client_order_id":"$it"""" } ?: "") +
                (reason?.let { ""","reason":$it""" } ?: "") + "}"

        /** One open position row as `/get_positions` reports it. */
        fun position(
            ticket: Long,
            volume: String,
            priceOpen: String = "2400",
            type: Int = 0,
            symbol: String = "XAUUSDm",
            magic: Int = 10001,
            comment: String = "",
            clientOrderId: String? = null,
        ): String =
            """{"ticket":$ticket,"symbol":"$symbol","type":$type,"volume":"$volume","price_open":"$priceOpen",""" +
                """"sl":"0","tp":"0","profit":"0","magic":$magic,"comment":"$comment"""" +
                (clientOrderId?.let { ""","client_order_id":"$it"""" } ?: "") + "}"
    }
}
