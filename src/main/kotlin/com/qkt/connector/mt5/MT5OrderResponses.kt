package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Response

/** Reads the gateway's replies to order, cancel, modify and close requests. */
internal class MT5OrderResponses(
    private val json: Json,
) {
    fun errorResponse(message: String): MT5OrderResponse =
        MT5OrderResponse(
            result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
            errorMessage = message,
        )

    fun parseOrderResponse(raw: String): MT5OrderResponse {
        val obj = json.parseToJsonElement(raw).jsonObject
        val r =
            obj["result"]?.jsonObject
                ?: return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "missing result field: $raw",
                )
        return MT5OrderResponse(
            result =
                MT5OrderResult(
                    retcode = r["retcode"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: -1,
                    order = r["order"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    deal = r["deal"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
                    price = r["price"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull() ?: BigDecimal.ZERO,
                    comment = r["comment"]?.jsonPrimitive?.contentOrNull ?: "",
                    volume = r["volume"]?.jsonPrimitive?.contentOrNull?.toBigDecimalOrNull(),
                ),
            errorMessage = obj["error"]?.jsonPrimitive?.contentOrNull,
        )
    }

    fun parseCancelResponse(raw: String): MT5OrderResponse {
        val obj =
            runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull()
                ?: return errorResponse("invalid cancel response: $raw")
        if (obj["result"] is JsonObject) return parseOrderResponse(raw)
        val message = obj["message"]?.jsonPrimitive?.contentOrNull.orEmpty()
        if (message.contains("cancel", ignoreCase = true)) {
            return MT5OrderResponse(
                result =
                    MT5OrderResult(
                        retcode = MT5_TRADE_RETCODE_DONE,
                        order = 0,
                        deal = 0,
                        price = BigDecimal.ZERO,
                        comment = message,
                    ),
            )
        }
        return errorResponse(obj["error"]?.jsonPrimitive?.contentOrNull ?: "unconfirmed cancel response: $raw")
    }

    /** [parseAsyncMutationResponse] plus the NO_CHANGES-to-success modify normalization. */
    fun parseAsyncModifyResponse(
        ticket: Long,
        response: Response,
    ): MT5OrderResponse {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            noChangesSuccess(ticket, raw)?.let { return it }
            return errorResponse("HTTP ${response.code}: $raw")
        }
        return runCatching { parseOrderResponse(raw) }
            .getOrElse { error ->
                errorResponse("invalid gateway response after send: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    fun parseAsyncMutationResponse(response: Response): MT5OrderResponse {
        val raw = response.body?.string().orEmpty()
        if (!response.isSuccessful) return errorResponse("HTTP ${response.code}: $raw")
        return runCatching { parseOrderResponse(raw) }
            .getOrElse { error ->
                errorResponse("invalid gateway response after send: ${error.message ?: error.javaClass.simpleName}")
            }
    }

    /**
     * Normalizes a venue `NO_CHANGES` rejection of a protection modify into success:
     * the position already carries exactly the requested SL/TP (e.g. a fill-anchored
     * bracket update after a zero-slippage fill), so treating it as a failure would
     * arm a redundant engine-held fallback stop next to live venue protection.
     */
    fun noChangesSuccess(
        ticket: Long,
        raw: String,
    ): MT5OrderResponse? {
        val retcode =
            runCatching {
                json
                    .parseToJsonElement(raw)
                    .jsonObject["mt5_error"]
                    ?.jsonObject
                    ?.get("retcode")
                    ?.jsonPrimitive
                    ?.intOrNull
            }.getOrNull()
        if (retcode != MT5_TRADE_RETCODE_NO_CHANGES) return null
        return MT5OrderResponse(
            result =
                MT5OrderResult(
                    retcode = MT5_TRADE_RETCODE_DONE,
                    order = ticket,
                    deal = 0,
                    price = BigDecimal.ZERO,
                    comment = "no changes",
                ),
            errorMessage = null,
        )
    }
}
