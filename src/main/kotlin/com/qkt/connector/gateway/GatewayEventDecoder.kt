package com.qkt.connector.gateway

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/** Decodes each VGP v1 stream event into the handler for its type; an undefined type is a protocol error. */
internal class GatewayEventDecoder(
    private val onOrder: (WireOrder) -> Unit,
    private val onFill: (WireFill) -> Unit,
    private val onSettlement: (WireSettlement) -> Unit,
    private val onPosition: (WirePosition) -> Unit,
    private val onAccount: (WireAccount) -> Unit,
) {
    private val log = LoggerFactory.getLogger(GatewayEventDecoder::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Hands [event]'s data to its handler. */
    fun decode(event: WireEvent) {
        val data = event.data ?: throw GatewayProtocolException("${event.type} event without data")
        when (event.type) {
            "order" -> onOrder(json.decodeFromJsonElement(WireOrder.serializer(), data))
            "fill" -> onFill(json.decodeFromJsonElement(WireFill.serializer(), data))
            "settlement" -> onSettlement(json.decodeFromJsonElement(WireSettlement.serializer(), data))
            "position" -> onPosition(json.decodeFromJsonElement(WirePosition.serializer(), data))
            "account" -> onAccount(json.decodeFromJsonElement(WireAccount.serializer(), data))
            "kill" -> log.info("gateway kill switch: {}", data)
            else -> throw GatewayProtocolException("event type '${event.type}'")
        }
    }
}
