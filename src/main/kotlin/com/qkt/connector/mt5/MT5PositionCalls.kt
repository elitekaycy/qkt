package com.qkt.connector.mt5

import java.math.BigDecimal
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Closes open positions and moves their attached stop-loss and take-profit at the gateway.
 */
internal class MT5PositionCalls(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val apiKey: String?,
    private val json: Json,
    private val responses: MT5OrderResponses,
    private val readCache: MT5ReadCache?,
) {
    /**
     * Close an open position by its venue ticket via `POST /close_position`, optionally a
     * partial [volume]. This is how a hedging account is reduced without opening a counter
     * position. The gateway wraps `order_send` underneath, so a success mirrors the
     * `POST /order` `{"result":{...}}` shape; a non-2xx (e.g. a bad ticket returns
     * `{"error":...}`) is captured in [MT5OrderResponse.errorMessage]. Not retried —
     * a duplicate close is worse than a surfaced failure.
     */
    fun closePosition(
        ticket: Long,
        volume: BigDecimal? = null,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = closePositionJson(ticket, volume).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/close_position", apiKey)
                .post(body)
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    /**
     * Close a position without blocking the caller.
     *
     * With [partial] set, [volume] is required and the request uses the gateway's dedicated
     * `/position_close_partial` route. Otherwise it uses `/close_position`, the async sibling
     * of [closePosition]. [onResult] runs on an OkHttp dispatcher thread with the parsed
     * response, or a synthetic retcode -1 failure. Not retried.
     * Closes ride the engine thread (CLOSE rules, trailing stops, flattens), where a
     * blocking gateway round-trip stalls tick processing exactly when exits matter.
     */
    fun closePositionAsync(
        ticket: Long,
        volume: BigDecimal? = null,
        partial: Boolean = false,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val path: String
        val payload: String
        if (partial) {
            val closeVolume = requireNotNull(volume) { "partial close requires volume" }
            path = "/position_close_partial"
            payload = partialCloseJson(ticket, closeVolume)
        } else {
            path = "/close_position"
            payload = closePositionJson(ticket, volume)
        }
        val body = payload.toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl$path", apiKey)
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        onResult(responses.parseAsyncMutationResponse(it))
                    }
                }
            },
        )
    }

    /**
     * Modify an OPEN position's SL/TP via `POST /modify_sl_tp` (gateway `TRADE_ACTION_SLTP`).
     * This is how a trailing stop keeps a venue-side stop in place — push the new SL level onto
     * the position so the broker still protects it even if qkt is offline. Returns the standard
     * `{"result":{...}}` envelope; a non-2xx is captured in [MT5OrderResponse.errorMessage].
     *
     * The gateway treats an omitted `sl`/`tp` as `0.0`, which *clears* that level. To avoid
     * clearing the take-profit when only trailing the stop, pass the current [tp] alongside [sl].
     * Not retried — a duplicate modify is harmless but a surfaced failure is preferable to silent
     * retries racing the trail.
     */
    fun modifyPosition(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = modifyPositionJson(ticket, sl, tp).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/modify_sl_tp", apiKey)
                .post(body)
                .build()
        val resp =
            try {
                http.newCall(request).execute()
            } finally {
                readCache?.clear()
            }
        resp.use {
            val raw = it.body?.string().orEmpty()
            if (!it.isSuccessful) {
                val normalized = responses.noChangesSuccess(ticket, raw)
                if (normalized != null) return normalized
                return MT5OrderResponse(
                    result = MT5OrderResult(retcode = -1, order = 0, deal = 0, price = BigDecimal.ZERO, comment = ""),
                    errorMessage = "HTTP ${it.code}: $raw",
                )
            }
            return responses.parseOrderResponse(raw)
        }
    }

    /** Modify an open position's SL/TP on OkHttp's dispatcher without blocking the caller. */
    fun modifyPositionAsync(
        ticket: Long,
        sl: BigDecimal? = null,
        tp: BigDecimal? = null,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val body = modifyPositionJson(ticket, sl, tp).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/modify_sl_tp", apiKey)
                .post(body)
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        onResult(responses.parseAsyncModifyResponse(ticket, it))
                    }
                }
            },
        )
    }

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
