package com.qkt.connector.gateway

import com.qkt.marketdata.Tick
import com.qkt.marketdata.live.LiveTickSource
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.serialization.json.Json

/**
 * The quotes socket at [url] as a [LiveTickSource]: each quote of a venue code becomes a tick of its
 * qkt symbol ([qktSymbol]), after [onQuote] has seen it; a quote without a price gives no tick, and a
 * malformed one goes to `onError`.
 * Quotes carry no sequence, so a dropped socket only reconnects and subscribes again; the drop reaches
 * `onDisconnect` and the return `onReconnect`.
 */
internal class GatewayQuoteSource(
    private val url: String,
    private val apiKey: String,
    private val qktSymbol: (String) -> String,
    private val onQuote: (WireQuote) -> Unit = {},
    private val initialBackoffMs: Long = 1_000,
    private val maxBackoffMs: Long = 30_000,
) : LiveTickSource {
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile private var socket: GatewaySocket? = null

    override fun start(
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit,
    ) {
        val down = AtomicBoolean(false)
        socket =
            GatewaySocket(
                "quotes",
                apiKey,
                { url },
                onText = { text ->
                    runCatching { tickOf(text)?.let(onTick) }.onFailure(onError)
                },
                onConnection = { connected, _ ->
                    if (!connected) {
                        down.set(true)
                        onDisconnect()
                    } else if (down.compareAndSet(true, false)) {
                        onReconnect()
                    }
                },
                initialBackoffMs = initialBackoffMs,
                maxBackoffMs = maxBackoffMs,
            ).also { it.start() }
    }

    override fun stop() {
        socket?.stop()
    }

    private fun tickOf(text: String): Tick? {
        val quote = json.decodeFromString(WireQuote.serializer(), text)
        onQuote(quote)
        return gatewayQuoteTick(qktSymbol(quote.symbol), quote)
    }
}
