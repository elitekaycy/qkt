package com.qkt.connector.mt5

import com.qkt.execution.OrderRequest

/**
 * The stop-loss / take-profit an order asked the venue to attach, remembered per ticket so a later
 * venue-side change can be told apart from the engine's own request. E.g. a bracket buy with stop
 * 2390.00 and target 2420.00 yields (2390.00, 2420.00); a bare market order yields null.
 */
internal class MT5RequestedProtection(
    private val translator: MT5OrderTranslator,
) {
    fun protectionFor(request: OrderRequest): MT5PositionProtection? =
        runCatching { translator.translate(request) }
            .getOrNull()
            ?.let { translation ->
                (translation as? MT5Translation.Single)?.request?.let(::protectionOf)
            }

    fun protectionOf(request: MT5OrderRequest): MT5PositionProtection? =
        if (request.sl == null && request.tp == null) {
            null
        } else {
            MT5PositionProtection(request.sl, request.tp)
        }
}
