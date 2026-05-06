package com.qkt.broker.bybit

import java.math.BigDecimal
import kotlinx.serialization.json.JsonObject

class FakeBybitClient : BybitTransport {
    data class Posted(
        val path: String,
        val body: String,
    )

    val posts: MutableList<Posted> = mutableListOf()

    val responses: MutableMap<String, String> = mutableMapOf()

    private val topicListeners: MutableMap<String, MutableList<(JsonObject) -> Unit>> = mutableMapOf()
    private val disconnectListeners: MutableList<(String) -> Unit> = mutableListOf()
    private val onReconnectListeners: MutableList<() -> Unit> = mutableListOf()

    override var isConnected: Boolean = true

    private var balancesCache: Map<String, BigDecimal> = emptyMap()

    override val balances: Map<String, BigDecimal>
        get() = balancesCache

    override fun updateBalances(snapshot: Map<String, BigDecimal>) {
        balancesCache = snapshot.toMap()
    }

    override fun postSigned(
        path: String,
        jsonBody: String,
    ): String {
        posts.add(Posted(path, jsonBody))
        return responses[path]
            ?: """{"retCode":0,"retMsg":"OK","result":{}}"""
    }

    override fun subscribe(
        topic: String,
        listener: (JsonObject) -> Unit,
    ) {
        topicListeners.getOrPut(topic) { mutableListOf() }.add(listener)
    }

    override fun onDisconnect(handler: (String) -> Unit) {
        disconnectListeners.add(handler)
    }

    override fun onReconnect(handler: () -> Unit) {
        onReconnectListeners.add(handler)
    }

    fun emitWsFrame(
        topic: String,
        json: JsonObject,
    ) {
        topicListeners[topic]?.forEach { it(json) }
    }

    fun emitDisconnect(reason: String) {
        isConnected = false
        disconnectListeners.forEach { it(reason) }
    }

    fun fireOnReconnect() {
        isConnected = true
        onReconnectListeners.forEach { it() }
    }
}
