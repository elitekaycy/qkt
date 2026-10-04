package com.qkt.connector.bybit

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * What a Bybit execution record is, by its `execType`
 * (https://bybit-exchange.github.io/docs/v5/enum#exectype): only [FILL] (`Trade`) is an order of
 * ours filling. [FUNDING] (`Funding`) is a perpetual's funding fee settled on a held position: a cash
 * movement that changes no position. [OTHER] (`AdlTrade`, `BustTrade`, `Delivery`, `Settle`,
 * `BlockTrade`, `MovePosition`, ...) moves a position without an order of ours, which the position
 * reconcile corrects, so it is never a fill either.
 */
enum class BybitExecutionKind {
    FILL,
    FUNDING,
    OTHER,
    ;

    /** How [BybitExecutionKind.of] reads a record. */
    companion object {
        /** The kind of [execution], a `/v5/execution/list` item or a private `execution` frame entry. */
        fun of(execution: JsonObject): BybitExecutionKind =
            when (execution["execType"]?.jsonPrimitive?.content) {
                "Trade" -> FILL
                "Funding" -> FUNDING
                else -> OTHER
            }
    }
}
