package com.qkt.connector.mt5

import java.math.BigDecimal
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.slf4j.LoggerFactory

/**
 * Cancels and modifies orders that are already resting at the gateway.
 */
internal class MT5WorkingOrderCalls(
    private val http: OkHttpClient,
    private val gatewayUrl: String,
    private val apiKey: String?,
    private val responses: MT5OrderResponses,
    private val readCache: MT5ReadCache?,
) {
    private val log = LoggerFactory.getLogger(MT5Client::class.java)

    /**
     * Cancel a working order via `DELETE /orders/{ticket}`.
     *
     * Returns the raw response body on success, or an empty string on HTTP failure.
     * The caller logs a warning when the body indicates a non-success retcode so a
     * 404/4xx no longer masquerades as a successful cancel — the prior `POST /cancel/...`
     * shape silently succeeded against gateways that returned an HTML 404 page.
     */
    fun cancelOrder(ticket: Long): String {
        readCache?.clear()
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .delete()
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
                log.warn("MT5Client cancelOrder($ticket) HTTP ${it.code}: $raw")
                return ""
            }
            return raw
        }
    }

    /**
     * Cancel a pending order without blocking the caller.
     *
     * [onResult] receives a parsed venue result. HTTP and I/O failures are represented by a
     * response with retcode `-1` and a populated [MT5OrderResponse.errorMessage], matching
     * [placeOrderAsync]. Legacy gateways that confirm cancellation with only a success message
     * are normalized to [MT5_TRADE_RETCODE_DONE].
     */
    fun cancelOrderAsync(
        ticket: Long,
        onResult: (MT5OrderResponse) -> Unit,
    ) {
        readCache?.clear()
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .delete()
                .build()
        http.newCall(request).enqueue(
            object : Callback {
                override fun onFailure(
                    call: Call,
                    e: java.io.IOException,
                ) {
                    readCache?.clear()
                    log.warn("MT5Client cancelOrder($ticket) IO error: ${e.message}")
                    onResult(responses.errorResponse("IO error: ${e.message}"))
                }

                override fun onResponse(
                    call: Call,
                    response: Response,
                ) {
                    readCache?.clear()
                    response.use {
                        val raw = it.body?.string().orEmpty()
                        if (!it.isSuccessful) {
                            log.warn("MT5Client cancelOrder($ticket) HTTP ${it.code}: $raw")
                            onResult(responses.errorResponse("HTTP ${it.code}: $raw"))
                        } else {
                            onResult(responses.parseCancelResponse(raw))
                        }
                    }
                }
            },
        )
    }

    /**
     * Modify a working order via `PUT /orders/{ticket}`. Returns the gateway's
     * [MT5OrderResponse] — successful when [MT5OrderResult.retcode] is
     * `MT5_TRADE_RETCODE_DONE`. A non-2xx response is captured in [MT5OrderResponse.errorMessage]
     * so the broker layer can reject deterministically.
     */
    fun modifyOrder(
        ticket: Long,
        mods: MT5OrderModification,
    ): MT5OrderResponse {
        readCache?.clear()
        val body = orderModificationJson(mods).toRequestBody(JSON_MEDIA)
        val request =
            mt5RequestBuilder("$gatewayUrl/orders/$ticket", apiKey)
                .put(body)
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

    private companion object {
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
