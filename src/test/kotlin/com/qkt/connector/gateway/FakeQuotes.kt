package com.qkt.connector.gateway

import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse

/**
 * The quotes socket of a [FakeGateway] (`GET /v1/quotes`): each connection subscribes to its `symbols`
 * and `roots`, and [send] reaches the connections subscribed to the quote's code or its root (the
 * code's first `-` field). Nothing is replayed. [drop] closes every connection, as a gateway restart would.
 */
internal class FakeQuotes {
    private val json = Json { encodeDefaults = true }
    private val sockets = CopyOnWriteArrayList<Pair<WebSocket, (String) -> Boolean>>()

    /** The query of every subscription, in order. */
    val subscriptions = CopyOnWriteArrayList<String>()

    /** How many connections are open. */
    val open: Int get() = sockets.size

    /** Accepts one connection subscribing to [url]'s `symbols` and `roots`. */
    fun upgrade(url: HttpUrl): MockResponse {
        val symbols = listed(url, "symbols")
        val roots = listed(url, "roots")
        subscriptions += url.encodedQuery.orEmpty()
        val wants = { code: String -> code in symbols || code.substringBefore('-') in roots }
        return MockResponse().withWebSocketUpgrade(
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    sockets += webSocket to wants
                }
            },
        )
    }

    private fun listed(
        url: HttpUrl,
        name: String,
    ): Set<String> = url.queryParameter(name)?.split(',')?.toSet() ?: emptySet()

    /** Sends [quote] to every connection subscribed to it. */
    fun send(quote: WireQuote) {
        val text = json.encodeToString(WireQuote.serializer(), quote)
        sockets.filter { (_, wants) -> wants(quote.symbol) }.forEach { (socket, _) -> socket.send(text) }
    }

    /** Sends raw [text] to every connection. */
    fun sendRaw(text: String) = sockets.forEach { (socket, _) -> socket.send(text) }

    /** Closes every connection. */
    fun drop() {
        sockets.forEach { (socket, _) -> socket.close(1001, "gateway restarting") }
        sockets.clear()
    }
}
