package com.qkt.connector.gateway

import kotlinx.serialization.json.Json
import org.slf4j.LoggerFactory

/**
 * Decodes each VGP v1 stream event into the handler for its type. A type qkt does not know is logged and
 * ignored, as the wire spec has clients do, so a gateway can add events without breaking this client.
 */
internal class GatewayEventDecoder(
    private val onOrder: (WireOrder) -> Unit,
    private val onFill: (WireFill) -> Unit,
    private val onSettlement: (WireSettlement) -> Unit,
    private val onPosition: (WirePosition) -> Unit,
    private val onAccount: (WireAccount) -> Unit,
    private val onFunding: (WireFunding) -> Unit,
) {
    /** Decodes into [ledger] (orders, fills, settlements), [account] (positions, account) and [onFunding]. */
    constructor(
        ledger: GatewayLedger,
        account: GatewayAccountState,
        onFunding: (WireFunding) -> Unit,
    ) : this(ledger::onOrder, ledger::onFill, ledger::onSettlement, account::position, account::account, onFunding)

    private val log = LoggerFactory.getLogger(GatewayEventDecoder::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    /** Hands [event]'s data to its handler. */
    fun decode(event: WireEvent) {
        if (event.type !in KNOWN) return log.warn("gateway event type '{}' is not one qkt knows; ignored", event.type)
        val data = event.data ?: throw GatewayProtocolException("${event.type} event without data")
        when (event.type) {
            "order" -> onOrder(json.decodeFromJsonElement(WireOrder.serializer(), data))
            "fill" -> onFill(json.decodeFromJsonElement(WireFill.serializer(), data))
            "settlement" -> onSettlement(json.decodeFromJsonElement(WireSettlement.serializer(), data))
            "position" -> onPosition(json.decodeFromJsonElement(WirePosition.serializer(), data))
            "account" -> onAccount(json.decodeFromJsonElement(WireAccount.serializer(), data))
            "funding" -> onFunding(json.decodeFromJsonElement(WireFunding.serializer(), data))
            "kill" -> log.info("gateway kill switch: {}", data)
        }
    }

    private companion object {
        val KNOWN = setOf("order", "fill", "settlement", "position", "account", "funding", "kill")
    }
}
