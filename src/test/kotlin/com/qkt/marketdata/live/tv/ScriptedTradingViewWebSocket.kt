package com.qkt.marketdata.live.tv

import java.util.concurrent.CopyOnWriteArrayList

/**
 * A TradingView connection that answers synchronously: when [method] is sent, [answer] runs on the
 * sending thread with the command's parameters and may push frames or lifecycle events to listeners.
 */
internal class ScriptedTradingViewWebSocket(
    private val method: String = "create_series",
    private val failOn: String? = null,
    private val answer: ScriptedTradingViewWebSocket.(List<Any>) -> Unit,
) : TradingViewWebSocketLike {
    val listeners: MutableList<TradingViewListener> = CopyOnWriteArrayList()
    val sent: MutableList<Pair<String, List<Any>>> = mutableListOf()
    var closed = false

    override fun addListener(listener: TradingViewListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: TradingViewListener) {
        listeners.remove(listener)
    }

    override fun send(
        method: String,
        params: List<Any>,
    ) {
        sent += method to params
        if (method == failOn) throw IllegalStateException("socket gone")
        if (method == this.method) this.answer(params)
    }

    override fun close() {
        closed = true
    }

    fun push(json: String) = listeners.forEach { it.onFrame(TradingViewFrame.parse(json)) }

    fun connected() = listeners.forEach { it.onConnected() }

    fun disconnected(reason: String) = listeners.forEach { it.onDisconnected(reason) }
}

/** A `timescale_update` carrying [rows] (each the JSON of a `v` array) for [sessionId]/[seriesId]. */
internal fun timescaleUpdate(
    sessionId: Any,
    seriesId: Any,
    vararg rows: String,
): String {
    val s = rows.mapIndexed { i, v -> """{"i":$i,"v":$v}""" }.joinToString(",")
    return """{"m":"timescale_update","p":["$sessionId",{"$seriesId":{"s":[$s]}}]}"""
}
