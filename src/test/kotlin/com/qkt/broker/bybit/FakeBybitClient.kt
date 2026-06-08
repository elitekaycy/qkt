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

    val responsesByPredicate: MutableList<Pair<(String, String) -> Boolean, String>> = mutableListOf()

    val dynamicResponses: MutableList<Pair<(String, String) -> Boolean, () -> String>> = mutableListOf()

    /** Async posts whose (path, body) match the predicate fail with the paired error. */
    val asyncFailures: MutableList<Pair<(String, String) -> Boolean, Throwable>> = mutableListOf()

    private val topicListeners: MutableMap<String, MutableList<(JsonObject) -> Unit>> = mutableMapOf()
    private val disconnectListeners: MutableList<(String) -> Unit> = mutableListOf()
    private val onReconnectListeners: MutableList<() -> Unit> = mutableListOf()

    override var isConnected: Boolean = true

    override var accountType: String = "UNIFIED"

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
        val dynMatched = dynamicResponses.firstOrNull { it.first(path, jsonBody) }
        if (dynMatched != null) return dynMatched.second()
        val matched = responsesByPredicate.firstOrNull { it.first(path, jsonBody) }
        if (matched != null) return matched.second
        return responses[path]
            ?: """{"retCode":0,"retMsg":"OK","result":{}}"""
    }

    override fun postSignedAsync(
        path: String,
        jsonBody: String,
        onResult: (Result<String>) -> Unit,
    ) {
        posts.add(Posted(path, jsonBody))
        val failure = asyncFailures.firstOrNull { it.first(path, jsonBody) }
        if (failure != null) {
            onResult(Result.failure(failure.second))
            return
        }
        val dynMatched = dynamicResponses.firstOrNull { it.first(path, jsonBody) }
        val matched = responsesByPredicate.firstOrNull { it.first(path, jsonBody) }
        val body =
            dynMatched?.second?.invoke()
                ?: matched?.second
                ?: responses[path]
                ?: """{"retCode":0,"retMsg":"OK","result":{}}"""
        onResult(Result.success(body))
    }

    override fun getSigned(
        path: String,
        query: Map<String, String>,
    ): String {
        val q = query.entries.joinToString("&") { "${it.key}=${it.value}" }
        posts.add(Posted(path, q))
        val dynMatched = dynamicResponses.firstOrNull { it.first(path, q) }
        if (dynMatched != null) return dynMatched.second()
        val matched = responsesByPredicate.firstOrNull { it.first(path, q) }
        if (matched != null) return matched.second
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
