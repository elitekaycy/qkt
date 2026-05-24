package com.qkt.broker.mt5

import com.qkt.common.Side
import com.qkt.execution.OrderRequest
import com.qkt.execution.TrailMode
import com.qkt.marketdata.MarketPriceProvider
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

/**
 * Converts qkt [OrderRequest]s into the JSON wire shape understood by `mt5-gateway`.
 *
 * [priceTracker] is needed for `PERCENT` mode trailing stops — the translator needs
 * the current mid-price to compute an absolute distance at submit time. `null` means
 * PERCENT mode rejects with a clear error (Phase 26b behavior preserved when
 * the broker isn't configured with a tracker).
 */
class MT5OrderTranslator(
    private val profile: MT5BrokerProfile,
    private val symbol: MT5Symbol,
    private val priceTracker: MarketPriceProvider? = null,
) {
    private val prefix: String = "${profile.name.uppercase()}:"

    private fun bare(qktSymbol: String): String = qktSymbol.removePrefix(prefix)

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
            symbol = symbol.toBroker(bare(req.symbol)),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY" else "SELL",
            price = null,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
        )

    private fun translateBracket(req: OrderRequest.Bracket): MT5OrderRequest {
        // Dispatch the entry order type — a Bracket can be a market entry (BUY/SELL)
        // OR a pending entry (BUY_STOP/SELL_STOP, BUY_LIMIT/SELL_LIMIT). Previously
        // every bracket translated to a market order regardless, which silently
        // dropped the entry trigger for STOP-entry brackets (the hedge-straddle
        // shape). MT5 accepts SL/TP fields on pending orders, so we attach
        // [req.stopLoss] / [req.takeProfit] alongside the entry type.
        val (type, price) =
            when (val entry = req.entry) {
                is OrderRequest.Market ->
                    (if (req.side == Side.BUY) "BUY" else "SELL") to null
                is OrderRequest.Stop ->
                    (if (req.side == Side.BUY) "BUY_STOP" else "SELL_STOP") to entry.stopPrice
                is OrderRequest.Limit ->
                    (if (req.side == Side.BUY) "BUY_LIMIT" else "SELL_LIMIT") to entry.limitPrice
                else -> error("MT5 bracket entry must be Market/Stop/Limit, got ${entry::class.simpleName}")
            }
        return MT5OrderRequest(
            symbol = symbol.toBroker(bare(req.symbol)),
            volume = req.quantity,
            type = type,
            price = price,
            sl = req.stopLoss,
            tp = req.takeProfit,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
            expiration = req.expiresAt?.let { it / 1000 },
        )
    }

    private fun translateStop(req: OrderRequest.Stop): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(bare(req.symbol)),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_STOP" else "SELL_STOP",
            price = req.stopPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
            expiration = req.expiresAt?.let { it / 1000 },
        )

    private fun translateLimit(req: OrderRequest.Limit): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(bare(req.symbol)),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_LIMIT" else "SELL_LIMIT",
            price = req.limitPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
            expiration = req.expiresAt?.let { it / 1000 },
        )

    private fun translateStopLimit(req: OrderRequest.StopLimit): MT5OrderRequest =
        MT5OrderRequest(
            symbol = symbol.toBroker(bare(req.symbol)),
            volume = req.quantity,
            type = if (req.side == Side.BUY) "BUY_STOP_LIMIT" else "SELL_STOP_LIMIT",
            price = req.stopPrice,
            stopLimit = req.limitPrice,
            sl = null,
            tp = null,
            deviation = profile.deviationPoints,
            magic = profile.magic,
            comment = req.id,
            expiration = req.expiresAt?.let { it / 1000 },
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
        // Resolve absolute distance first — PERCENT errors should surface before unrelated
        // profile-config errors so the user knows exactly which knob is wrong.
        val absoluteDistance: BigDecimal =
            when (req.trailMode) {
                TrailMode.ABSOLUTE -> req.trailAmount
                TrailMode.PERCENT -> {
                    val tracker =
                        priceTracker
                            ?: error(
                                "PERCENT trailing requires a MarketPriceProvider; " +
                                    "configure the broker constructor with priceTracker " +
                                    "or use TrailMode.ABSOLUTE",
                            )
                    val mid =
                        tracker.lastPrice(req.symbol)
                            ?: error(
                                "PERCENT trailing requires lastPrice for ${req.symbol}; " +
                                    "ensure the tick stream is active before submitting",
                            )
                    mid.multiply(req.trailAmount).divide(BigDecimal(100), MathContext.DECIMAL64)
                }
            }
        val point = pointFor(bare(req.symbol))
        val distancePoints =
            absoluteDistance
                .divide(point, MathContext.DECIMAL64)
                .setScale(0, RoundingMode.HALF_UP)
                .toLong()
        require(distancePoints > 0) {
            "TrailingStop distance $absoluteDistance resolves to 0 MT5 points (point size $point); too tight"
        }
        return MT5OrderRequest(
            symbol = symbol.toBroker(bare(req.symbol)),
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
