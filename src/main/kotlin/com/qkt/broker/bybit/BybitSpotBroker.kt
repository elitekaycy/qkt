package com.qkt.broker.bybit

import com.qkt.broker.Broker
import com.qkt.broker.OrderModification
import com.qkt.broker.OrderTypeCapability
import com.qkt.broker.SubmitAck
import com.qkt.bus.EventBus
import com.qkt.common.Clock
import com.qkt.common.Money
import com.qkt.common.Side
import com.qkt.events.BrokerEvent
import com.qkt.execution.OrderRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory

class BybitSpotBroker(
    private val transport: BybitTransport,
    private val bus: EventBus,
    private val clock: Clock,
) : Broker {
    private val log = LoggerFactory.getLogger(BybitSpotBroker::class.java)
    private val json = Json { ignoreUnknownKeys = true }

    private val symbolByClientOrderId: MutableMap<String, String> = mutableMapOf()

    override val name: String = "BybitSpot"

    override val capabilities: Set<OrderTypeCapability> =
        setOf(
            OrderTypeCapability.MARKET,
            OrderTypeCapability.LIMIT,
            OrderTypeCapability.STOP,
            OrderTypeCapability.STOP_LIMIT,
            OrderTypeCapability.IF_TOUCHED,
            OrderTypeCapability.MODIFY,
        )

    override fun supports(symbol: String): Boolean = symbol.startsWith("BYBIT_SPOT:")

    init {
        transport.subscribe("order") { frame -> onOrderFrame(frame) }
        transport.subscribe("execution") { frame -> onExecutionFrame(frame) }
    }

    override fun submit(request: OrderRequest): SubmitAck {
        if (!supports(request.symbol)) {
            return SubmitAck(
                clientOrderId = request.id,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "BybitSpotBroker does not support symbol ${request.symbol}",
            )
        }
        val body = BybitOrderTranslator.toCreateBody(request)
        val response =
            try {
                transport.postSigned("/v5/order/create", body)
            } catch (e: Exception) {
                log.warn("Bybit submit failed: {}", e.message)
                bus.publish(
                    BrokerEvent.OrderRejected(
                        clientOrderId = request.id,
                        brokerOrderId = null,
                        reason = e.message ?: "transport failure",
                        timestamp = clock.now(),
                    ),
                )
                return SubmitAck(
                    clientOrderId = request.id,
                    brokerOrderId = null,
                    accepted = false,
                    rejectReason = e.message ?: "transport failure",
                )
            }
        val ack = parseSubmitResponse(request.id, response)
        if (ack.accepted) symbolByClientOrderId[request.id] = request.symbol
        return ack
    }

    override fun cancel(orderId: String) {
        val symbol = symbolByClientOrderId[orderId] ?: return
        val body = BybitOrderTranslator.toCancelBody(symbol = symbol, orderLinkId = orderId)
        try {
            transport.postSigned("/v5/order/cancel", body)
        } catch (e: Exception) {
            log.warn("Bybit cancel failed for {}: {}", orderId, e.message)
        }
    }

    override fun modify(
        orderId: String,
        changes: OrderModification,
    ): SubmitAck {
        val symbol =
            symbolByClientOrderId[orderId]
                ?: return SubmitAck(orderId, null, accepted = false, rejectReason = "unknown orderId $orderId")
        val parsed = BybitSymbol.parse(symbol)
        val sb = StringBuilder("{")
        sb.append("\"category\":\"${parsed.category}\",")
        sb.append("\"symbol\":\"${parsed.bare}\",")
        sb.append("\"orderLinkId\":\"$orderId\"")
        if (changes.newQuantity != null) sb.append(",\"qty\":\"${changes.newQuantity.toPlainString()}\"")
        if (changes.newLimitPrice != null) sb.append(",\"price\":\"${changes.newLimitPrice.toPlainString()}\"")
        if (changes.newStopPrice != null) sb.append(",\"triggerPrice\":\"${changes.newStopPrice.toPlainString()}\"")
        sb.append("}")
        val response =
            try {
                transport.postSigned("/v5/order/amend", sb.toString())
            } catch (e: Exception) {
                return SubmitAck(orderId, null, accepted = false, rejectReason = e.message ?: "transport failure")
            }
        return parseSubmitResponse(orderId, response)
    }

    private fun parseSubmitResponse(
        clientOrderId: String,
        responseBody: String,
    ): SubmitAck {
        val tree = json.parseToJsonElement(responseBody).jsonObject
        val retCode = tree["retCode"]?.jsonPrimitive?.content?.toIntOrNull() ?: -1
        val retMsg = tree["retMsg"]?.jsonPrimitive?.content ?: ""
        if (retCode != 0) {
            bus.publish(
                BrokerEvent.OrderRejected(
                    clientOrderId = clientOrderId,
                    brokerOrderId = null,
                    reason = "$retCode: $retMsg",
                    timestamp = clock.now(),
                ),
            )
            return SubmitAck(
                clientOrderId = clientOrderId,
                brokerOrderId = null,
                accepted = false,
                rejectReason = "$retCode: $retMsg",
            )
        }
        val brokerOrderId =
            tree["result"]
                ?.jsonObject
                ?.get("orderId")
                ?.jsonPrimitive
                ?.content
        return SubmitAck(
            clientOrderId = clientOrderId,
            brokerOrderId = brokerOrderId,
            accepted = true,
        )
    }

    private fun onOrderFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val obj = entry.jsonObject
            val clientOrderId = obj["orderLinkId"]?.jsonPrimitive?.content ?: continue
            val brokerOrderId = obj["orderId"]?.jsonPrimitive?.content
            val status = obj["orderStatus"]?.jsonPrimitive?.content ?: continue
            val now = clock.now()
            when (status) {
                "New" ->
                    bus.publish(
                        BrokerEvent.OrderAccepted(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            timestamp = now,
                        ),
                    )
                "Cancelled" ->
                    bus.publish(
                        BrokerEvent.OrderCancelled(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = "broker cancel",
                            timestamp = now,
                        ),
                    )
                "Rejected" ->
                    bus.publish(
                        BrokerEvent.OrderRejected(
                            clientOrderId = clientOrderId,
                            brokerOrderId = brokerOrderId,
                            reason = obj["rejectReason"]?.jsonPrimitive?.content ?: "broker rejected",
                            timestamp = now,
                        ),
                    )
                else -> log.debug("Bybit order frame status={} (no event)", status)
            }
        }
    }

    private fun onExecutionFrame(frame: JsonObject) {
        val data = frame["data"]?.jsonArray ?: return
        for (entry in data) {
            val obj = entry.jsonObject
            val clientOrderId = obj["orderLinkId"]?.jsonPrimitive?.content ?: continue
            val brokerOrderId = obj["orderId"]?.jsonPrimitive?.content
            val bareSymbol = obj["symbol"]?.jsonPrimitive?.content ?: continue
            val sideStr = obj["side"]?.jsonPrimitive?.content ?: continue
            val price = obj["execPrice"]?.jsonPrimitive?.content?.toBigDecimal() ?: continue
            val qty = obj["execQty"]?.jsonPrimitive?.content?.toBigDecimal() ?: continue
            val side = if (sideStr == "Buy") Side.BUY else Side.SELL
            val qktSymbol = BybitSymbol.toQkt(category = "spot", bare = bareSymbol)
            bus.publish(
                BrokerEvent.OrderFilled(
                    clientOrderId = clientOrderId,
                    brokerOrderId = brokerOrderId,
                    symbol = qktSymbol,
                    side = side,
                    price = price.setScale(Money.SCALE, Money.ROUNDING),
                    quantity = qty.setScale(Money.SCALE, Money.ROUNDING),
                    timestamp = clock.now(),
                ),
            )
        }
    }
}
