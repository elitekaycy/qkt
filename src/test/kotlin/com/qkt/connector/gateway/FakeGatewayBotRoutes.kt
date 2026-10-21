package com.qkt.connector.gateway

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse

// The routes a one-shot `qkt bot` command uses beside the engine's, on a [FakeGateway]: a flatten, filled
// at once at [CLOSE_PRICE] (the venue's answer to a reduce-only market order), and an amend of a working order.

internal const val CLOSE_PRICE = "100"

private val routeJson = Json { encodeDefaults = true }

private fun JsonObject.text(key: String): String? = this[key]?.jsonPrimitive?.content

private fun order(order: WireOrder) = FakeWire.ok(routeJson.encodeToString(WireOrder.serializer(), order))

/** Fakes that name a close's order themselves, ignoring `client_order_id`, as the paper gateway 0.1.0 does. */
internal val namesItsCloses: MutableSet<FakeGateway> = java.util.Collections.newSetFromMap(java.util.WeakHashMap())

/** `POST /v1/positions/close`: idempotent on `client_order_id`, refused when nothing is held. */
internal fun FakeGateway.botClose(text: String): MockResponse {
    val body = Json.parseToJsonElement(text).jsonObject
    val code = requireNotNull(body.text("symbol"))
    val id = body.text("client_order_id")?.takeIf { this !in namesItsCloses } ?: "close.${venue.orders.size}.1"
    venue.orders[id]?.let { return order(it) }
    val held =
        venue.positions().firstOrNull { it.symbol == code }?.let { BigDecimal(it.quantity) }
            ?: return FakeWire.error(422, "no_position", "no position in $code")
    val quantity = body.text("quantity")?.let(::BigDecimal) ?: held.abs()
    val side = if (held.signum() > 0) "sell" else "buy"
    venue.place(WireSubmit(id, code, side, "market", quantity.toPlainString(), null, null, "ioc", reduceOnly = true))
    venue.fill(id, "f-$id", quantity.toPlainString(), CLOSE_PRICE, FakeGateway.TIME)
    return order(venue.orders.getValue(id))
}

/** `PATCH /v1/orders/{id}`: moves a working order's quantity and prices. */
internal fun FakeGateway.botAmend(
    id: String,
    text: String,
): MockResponse {
    val current = venue.orders[id] ?: return FakeWire.error(404, "not_found", "no order $id")
    val body = Json.parseToJsonElement(text).jsonObject
    val amended =
        current.copy(
            quantity = body.text("quantity") ?: current.quantity,
            limitPrice = body.text("limit_price") ?: current.limitPrice,
            stopPrice = body.text("stop_price") ?: current.stopPrice,
        )
    venue.orders[id] = amended
    return order(amended)
}
