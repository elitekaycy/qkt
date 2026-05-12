package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TrailMode
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * Result of translating a single qkt [OrderRequest] to the MT5 wire shape.
 *
 * Most shapes produce a [Single] wire request. Composite shapes — only [StandaloneOCO]
 * today — produce two requests that the broker submits as a group. The [Composite.groupId]
 * is the qkt-side identifier; both legs carry it in their [MT5OrderRequest.comment] so
 * the cancel-on-fill path can correlate sibling tickets without relying on a server-side
 * "group" concept (MT5 has none — each ticket is independent).
 */
sealed interface MT5Translation {
    data class Single(
        val request: MT5OrderRequest,
    ) : MT5Translation

    data class Composite(
        val requests: List<MT5OrderRequest>,
        val groupId: String,
    ) : MT5Translation
}

/** Converts qkt [OrderRequest]s into the JSON wire shape understood by `mt5-gateway`. */
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
) {
    fun translate(req: OrderRequest): MT5Translation =
        when (req) {
            is OrderRequest.Market -> MT5Translation.Single(translateMarket(req))
            is OrderRequest.Bracket -> MT5Translation.Single(translateBracket(req))
            is OrderRequest.Stop -> MT5Translation.Single(translateStop(req))
            is OrderRequest.Limit -> MT5Translation.Single(translateLimit(req))
            is OrderRequest.StopLimit -> MT5Translation.Single(translateStopLimit(req))
            is OrderRequest.TrailingStop -> MT5Translation.Single(translateTrailingStop(req))
            is OrderRequest.StandaloneOCO -> translateStandaloneOCO(req)
            else ->
                error("MT5 does not translate ${req::class.simpleName}; submit-time capability check should reject")
        }

    private fun translateMarket(req: OrderRequest.Market): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateBracket(req: OrderRequest.Bracket): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = req.stopLoss,
            tp = req.takeProfit,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateStop(req: OrderRequest.Stop): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_STOP" else "SELL_STOP",
            price = req.stopPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateLimit(req: OrderRequest.Limit): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_LIMIT" else "SELL_LIMIT",
            price = req.limitPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateStopLimit(req: OrderRequest.StopLimit): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_STOP_LIMIT" else "SELL_STOP_LIMIT",
            price = req.stopPrice,
            stopLimit = req.limitPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    /**
     * MT5 server-side trailing.
     *
     * The trailing stop attaches to a market entry order. [OrderRequest.TrailingStop]
     * doesn't carry a pending trigger price — the trail tracks the favorable side of
     * the price from the fill point.
     *
     * ABSOLUTE mode: converts the qkt trail distance (in price units) to MT5 points
     * via [MT5BrokerProfile.instrumentOverrides] or the protocol default.
     *
     * PERCENT mode: deferred to a follow-up phase. PERCENT requires the current
     * mid-price at submit time to compute the absolute distance; the translator
     * doesn't have access to a price tracker. Strategies using PERCENT trailing
     * on MT5 will fail with a clear error here until that work lands.
     */
    private fun translateTrailingStop(req: OrderRequest.TrailingStop): MT5OrderRequest {
        require(req.trailMode == TrailMode.ABSOLUTE) {
            "MT5 trailing stop currently supports ABSOLUTE trailAmount only; got ${req.trailMode}. " +
                "PERCENT mode needs a current-price seed at submit time (deferred)."
        }
        val point = pointFor(req.symbol)
        val distancePoints =
            req.trailAmount
                .divide(point, MathContext.DECIMAL64)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        require(distancePoints > 0) {
            "TrailingStop distance ${req.trailAmount} resolves to 0 MT5 points (point size $point); too tight"
        }
        return MT5OrderRequest(
            symbol = symbol.toBroker(req.symbol),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            slDistance = distancePoints,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )
    }

    /**
     * OCO: translate each leg recursively, then tag both with the qkt-side group id
     * so the broker layer can correlate them on fill events.
     *
     * MT5 native has no group concept; we encode in the comment prefix. The gateway
     * accepts arbitrary comment strings and surfaces them in `OnTradeTransaction`
     * events, letting the broker map ticket → group → sibling.
     */
    private fun translateStandaloneOCO(req: OrderRequest.StandaloneOCO): MT5Translation.Composite {
        val tag = "oco:${req.id}"
        val leg1 = (translate(req.leg1) as MT5Translation.Single).request
        val leg2 = (translate(req.leg2) as MT5Translation.Single).request
        return MT5Translation.Composite(
            requests =
                listOf(
                    leg1.copy(comment = "$tag/${leg1.comment}"),
                    leg2.copy(comment = "$tag/${leg2.comment}"),
                ),
            groupId = req.id,
        )
    }

    /** Resolve the qkt-side point size for the symbol from profile overrides. */
    private fun pointFor(qktSymbol: String): BigDecimal =
        profile.instrumentOverrides[qktSymbol]?.pointSize
            ?: error(
                "MT5 trailing requires instrumentOverrides[$qktSymbol].pointSize in the broker profile; " +
                    "add the instrument spec or omit trailing for this symbol",
            )
}
