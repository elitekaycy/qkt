package com.qkt.connector.mt5

import java.math.BigDecimal
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Places orders at the gateway. A placement is never retried: a duplicate order is worse than a
 * surfaced failure.
 */
internal class MT5OrderPlacement(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val apiKey: String?,
    private val responses: MT5OrderResponses,
    private val readCache: MT5ReadCache?,
) {
    fun placeOrder(req: MT5OrderRequest): MT5OrderResponse {
        readCache?.clear()
        val body = orderJson(req).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/order", apiKey)
                .header("Idempotency-Key", req.clientOrderId)
                .tag(TransportCorrelation::class.java, TransportCorrelation(req.engineOrderId))
                .post(body)
                .build()
        // POST /order is NOT retried: duplicate placement is worse than a surfaced failure.
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
     * Place an order WITHOUT blocking the caller. The HTTP send runs on OkHttp's dispatcher
     * (its own worker pool with a per-host cap); [onResult] is invoked on a dispatcher thread
     * with the parsed [MT5OrderResponse] on completion, or a synthetic failure response
     * (retcode -1, [MT5OrderResponse.errorMessage] set) on a non-2xx or IO error. Like
     * [placeOrder] the send is NOT retried — duplicate placement is worse than a surfaced
     * failure. This frees the engine thread from the order round-trip; the broker layer turns
     * [onResult] into the venue's `OrderAccepted`/`OrderRejected`/`OrderFilled` bus events.
     */
    fun placeOrderAsync(
        req: MT5OrderRequest,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val body = orderJson(req).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/order", apiKey)
                .header("Idempotency-Key", req.clientOrderId)
                .tag(TransportCorrelation::class.java, TransportCorrelation(req.engineOrderId))
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

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
