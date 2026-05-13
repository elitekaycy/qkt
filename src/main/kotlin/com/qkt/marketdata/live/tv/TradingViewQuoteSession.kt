package com.qkt.marketdata.live.tv

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class TradingViewQuoteSession(
    private val webSocket: TradingViewWebSocketLike,
    private val clock: Clock,
    private val sessionIdGenerator: () -> String = ::randomSessionId,
    private val fields: List<String> = DEFAULT_FIELDS,
) : TradingViewListener {
    private val log = LoggerFactory.getLogger(TradingViewQuoteSession::class.java)

    private val sessionId: String = sessionIdGenerator()
    private val symbols: MutableList<String> = mutableListOf()
    private val lastValues: MutableMap<String, MutableMap<String, BigDecimal>> = ConcurrentHashMap()

    private var onTick: ((Tick) -> Unit)? = null
    private var onError: ((Throwable) -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null
    private var hasDisconnected: Boolean = false

    fun subscribe(
        symbols: List<String>,
        onTick: (Tick) -> Unit,
        onError: (Throwable) -> Unit,
        onDisconnect: () -> Unit,
    ) {
        this.onTick = onTick
        this.onError = onError
        this.onDisconnect = onDisconnect
        this.symbols.addAll(symbols)
        webSocket.addListener(this)
        sendSubscribeCommands()
    }

    fun unsubscribe(symbols: List<String>) {
        if (symbols.isEmpty()) return
        webSocket.send("quote_remove_symbols", listOf(sessionId) + symbols)
        this.symbols.removeAll(symbols.toSet())
    }

    fun close() {
        webSocket.removeListener(this)
        symbols.clear()
        lastValues.clear()
    }

    override fun onFrame(frame: TradingViewFrame) {
        if (frame !is TradingViewFrame.Message) return
        if (frame.method != "qsd") return
        runCatching {
            val data = frame.paramAsObject(1)
            val name = data["n"]?.jsonPrimitive?.content ?: return
            val values = data["v"]?.jsonObject ?: return
            emitTick(name, values)
        }.onFailure { t ->
            log.warn("Cannot translate qsd frame: ${frame.toWireJson()}", t)
            onError?.invoke(t)
        }
    }

    override fun onConnected() {
        if (hasDisconnected && symbols.isNotEmpty()) {
            sendSubscribeCommands()
        }
    }

    override fun onDisconnected(reason: String) {
        log.warn("TradingViewQuoteSession disconnected: $reason")
        hasDisconnected = true
        onDisconnect?.invoke()
    }

    private fun sendSubscribeCommands() {
        webSocket.send("quote_create_session", listOf(sessionId))
        webSocket.send("quote_set_fields", listOf(sessionId) + fields)
        if (symbols.isNotEmpty()) {
            webSocket.send("quote_add_symbols", listOf(sessionId) + symbols)
        }
    }

    private fun emitTick(
        name: String,
        values: JsonObject,
    ) {
        val state = lastValues.getOrPut(name) { mutableMapOf() }
        for ((key, element) in values) {
            val numeric = element.jsonPrimitive.content.toBigDecimalOrNull() ?: continue
            state[key] = numeric
        }
        val price = state["lp"] ?: return
        val tick =
            Tick(
                symbol = name,
                price = price.setScale(Money.SCALE, Money.ROUNDING),
                timestamp = clock.now(),
                bid = state["bid"]?.setScale(Money.SCALE, Money.ROUNDING),
                ask = state["ask"]?.setScale(Money.SCALE, Money.ROUNDING),
                volume = state["volume"]?.setScale(Money.SCALE, Money.ROUNDING),
            )
        onTick?.invoke(tick)
    }

    companion object {
        val DEFAULT_FIELDS: List<String> = listOf("lp", "bid", "ask", "volume", "ch", "chp")

        private fun randomSessionId(): String {
            val chars = ('a'..'z') + ('0'..'9')
            return (1..8).map { chars.random(Random) }.joinToString("")
        }
    }
}
