package com.qkt.broker.bybit

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

    fun emitWsFrame(
        topic: String,
        json: JsonObject,
    ) {
        topicListeners[topic]?.forEach { it(json) }
    }

    fun emitDisconnect(reason: String) {
        disconnectListeners.forEach { it(reason) }
    }
}
