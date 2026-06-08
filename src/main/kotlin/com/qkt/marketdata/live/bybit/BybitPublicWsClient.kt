package com.qkt.marketdata.live.bybit

import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.SystemClock
import com.qkt.marketdata.Tick
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory

/**
 * Subscribes to Bybit public WS streams (`tickers.<symbol>` for bid/ask state and
 * `publicTrade.<symbol>` for last-trade prints), composes the two into a `Tick` per
 * symbol, and routes ticks to a sink callback.
 *
 * Reconnect-safe: tracks [hasDisconnected] so `onConnected()` only resends subscribe
 * commands on actual reconnect, never on the initial WS open after [subscribe] has
 * already manually sent them. (Same pattern as
 * [com.qkt.marketdata.live.tv.TradingViewQuoteSession].)
 */
class BybitPublicWsClient(
    private val ws: BybitPublicWsLike,
    private val clock: Clock = SystemClock(),
) : BybitPublicListener {
    private val log = LoggerFactory.getLogger(BybitPublicWsClient::class.java)
    private val symbols: MutableList<String> = mutableListOf()
    private val state: ConcurrentHashMap<String, MutableMap<String, BigDecimal>> = ConcurrentHashMap()
    private var onTick: ((Tick) -> Unit)? = null
    private var onDisconnect: (() -> Unit)? = null
    private var onReconnect: (() -> Unit)? = null

    @Volatile
    private var hasDisconnected: Boolean = false

    fun subscribe(
        symbols: List<String>,
        onTick: (Tick) -> Unit,
        onDisconnect: () -> Unit,
        onReconnect: () -> Unit = {},
    ) {
        this.symbols.addAll(symbols)
        this.onTick = onTick
        this.onDisconnect = onDisconnect
        this.onReconnect = onReconnect
        ws.addListener(this)
        sendSubscribe()
    }

    fun close() {
        ws.removeListener(this)
        ws.close()
    }

    override fun onFrame(frame: BybitPublicFrame) {
        when (frame) {
            is BybitPublicFrame.Tickers -> {
                val s = state.computeIfAbsent(frame.symbol) { ConcurrentHashMap() }
                s["bid"] = frame.bid
                s["ask"] = frame.ask
                s["last"] = frame.last
                emit(frame.symbol, brokerTimeMs = null)
            }
            is BybitPublicFrame.Trade -> {
                val s = state.computeIfAbsent(frame.symbol) { ConcurrentHashMap() }
                s["last"] = frame.price
                s["volume"] = frame.volume
                emit(frame.symbol, brokerTimeMs = frame.brokerTimeMs)
            }
            is BybitPublicFrame.SubscribeAck -> if (!frame.success) log.warn("subscribe rejected: ${frame.message}")
            is BybitPublicFrame.Unknown -> { /* ignore */ }
        }
    }

    override fun onConnected() {
        if (hasDisconnected && symbols.isNotEmpty()) {
            sendSubscribe()
            onReconnect?.invoke()
        }
    }

    override fun onDisconnected(reason: String) {
        log.warn("Bybit public WS disconnected: $reason")
        hasDisconnected = true
        onDisconnect?.invoke()
    }

    private fun emit(
        symbol: String,
        brokerTimeMs: Long?,
    ) {
        val s = state[symbol] ?: return
        val last = s["last"] ?: return
        onTick?.invoke(
            Tick(
                symbol = symbol,
                price = last.setScale(Money.SCALE, Money.ROUNDING),
                timestamp = brokerTimeMs ?: clock.now(),
                bid = s["bid"]?.setScale(Money.SCALE, Money.ROUNDING),
                ask = s["ask"]?.setScale(Money.SCALE, Money.ROUNDING),
                volume = s["volume"]?.setScale(Money.SCALE, Money.ROUNDING),
            ),
        )
    }

    private fun sendSubscribe() {
        val args = symbols.flatMap { listOf("tickers.$it", "publicTrade.$it") }
        val body =
            buildJsonObject {
                put("op", "subscribe")
                put("args", buildJsonArray { args.forEach { add(it) } })
            }
        ws.send(body.toString())
    }
}
