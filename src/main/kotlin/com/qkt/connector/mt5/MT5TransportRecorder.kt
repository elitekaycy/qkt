package com.qkt.connector.mt5

import okhttp3.Interceptor
import okhttp3.Response
import okio.Buffer
import org.slf4j.LoggerFactory

/** Tags a request with the engine order it belongs to, so the transport journal can correlate it. */
internal data class TransportCorrelation(
    val engineOrderId: String,
)

/**
 * Records every gateway exchange in the [MT5TransportJournal] without ever affecting the request:
 * a capture failure is logged and swallowed.
 */
internal class MT5TransportRecorder(
    private val journal: MT5TransportJournal,
    private val monotonicNanos: () -> Long,
) : Interceptor {
    private val log = LoggerFactory.getLogger(MT5Client::class.java)

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val startedNs = monotonicNanos()
        val response =
            try {
                chain.proceed(request)
            } catch (error: java.io.IOException) {
                recordTransportExchange(
                    request = request,
                    responseCode = null,
                    responseBody = null,
                    responseBytes = null,
                    error = error.message ?: error.javaClass.simpleName,
                    startedNs = startedNs,
                )
                throw error
            }
        // A successful snapshot read (account, positions, orders, deals) is
        // re-polled every second and its body is venue state the engine already
        // projects elsewhere; journaling it was ~99% of transport volume. Keep
        // the exchange, drop the body. Mutations and failures keep everything.
        val elideBody = request.method == "GET" && response.isSuccessful
        recordTransportExchange(
            request = request,
            responseCode = response.code,
            responseBody =
                if (elideBody) {
                    null
                } else {
                    runCatching { response.peekBody(MAX_CAPTURE_BODY_BYTES).string() }.getOrNull()
                },
            responseBytes = if (elideBody) response.body?.contentLength() else null,
            error = null,
            startedNs = startedNs,
        )
        return response
    }

    private fun recordTransportExchange(
        request: okhttp3.Request,
        responseCode: Int?,
        responseBody: String?,
        responseBytes: Long?,
        error: String?,
        startedNs: Long,
    ) {
        runCatching {
            journal.record(
                method = request.method,
                path =
                    request.url.encodedPath +
                        request.url.encodedQuery
                            ?.let { "?$it" }
                            .orEmpty(),
                requestBody = captureRequestBody(request),
                responseCode = responseCode,
                responseBody = responseBody,
                responseBytes = responseBytes,
                error = error,
                durationMs = (monotonicNanos() - startedNs) / 1_000_000L,
                idempotencyKey = request.header("Idempotency-Key"),
                engineOrderId = request.tag(TransportCorrelation::class.java)?.engineOrderId,
            )
        }.onFailure { captureError ->
            log.error("MT5 transport capture failed without affecting request: {}", captureError.message)
        }
    }

    private fun captureRequestBody(request: okhttp3.Request): String? {
        val body = request.body ?: return null
        if (body.isOneShot()) return "<one-shot body omitted>"
        return runCatching {
            val buffer = Buffer()
            body.writeTo(buffer)
            val size = buffer.size.coerceAtMost(MAX_CAPTURE_BODY_BYTES)
            buffer.readUtf8(size)
        }.getOrNull()
    }

    private companion object {
        const val MAX_CAPTURE_BODY_BYTES = 64L * 1024L
    }
}
