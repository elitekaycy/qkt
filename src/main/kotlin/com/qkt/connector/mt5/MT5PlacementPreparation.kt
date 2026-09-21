package com.qkt.connector.mt5

import com.qkt.marketdata.MarketPriceProvider
import java.math.BigDecimal
import java.math.RoundingMode
import org.slf4j.LoggerFactory

/**
 * Shapes an order to what the venue will accept before it is sent: volume rounded down to
 * the symbol's step, prices rounded to its digits, and entries or stops that sit inside the
 * venue's minimum stop distance refused locally with a readable reason. E.g. 0.137 lots on
 * a 0.01-step symbol goes out as 0.13; a stop 3 points from entry on a 20-point stops level
 * is refused without a gateway round-trip. [symbolMeta] is the broker's shared symbol cache.
 */
internal class MT5PlacementPreparation(
    private val profile: MT5BrokerProfile,
    private val client: MT5Client,
    private val priceTracker: MarketPriceProvider?,
    private val mt5Symbol: MT5Symbol,
    private val symbolMeta: MutableMap<String, MT5SymbolInfo>,
) {
    private val log = LoggerFactory.getLogger(MT5Broker::class.java)

    /**
     * Outcome of the pre-placement preparation step. [Ok] carries the quantized wire
     * shape; [Reject] carries a venue-specific reason so the broker layer can emit a
     * descriptive [BrokerEvent.OrderRejected] without relying on the gateway round-trip.
     */
    sealed interface PrepareResult {
        data class Ok(
            val wire: MT5OrderRequest,
        ) : PrepareResult

        data class Reject(
            val reason: String,
        ) : PrepareResult
    }

    sealed interface VolumeResult {
        data class Ok(
            val quantity: BigDecimal,
        ) : VolumeResult

        data class Reject(
            val reason: String,
        ) : VolumeResult
    }

    fun prepareVolume(
        brokerSymbol: String,
        quantity: BigDecimal,
    ): VolumeResult {
        val rules = resolveVenueRules(brokerSymbol) ?: return VolumeResult.Ok(quantity)
        val quantized =
            if (rules.volumeStep.signum() > 0) {
                quantity.divide(rules.volumeStep, 0, RoundingMode.DOWN).multiply(rules.volumeStep)
            } else {
                quantity
            }
        return when {
            quantized < rules.volumeMin ->
                VolumeResult.Reject(
                    "quantized volume below venue volumeMin for $brokerSymbol (input=${quantity.toPlainString()})",
                )
            rules.volumeMax != null && quantized > rules.volumeMax ->
                VolumeResult.Reject(
                    "quantized volume above venue volumeMax for $brokerSymbol (input=${quantity.toPlainString()})",
                )
            else -> VolumeResult.Ok(quantized)
        }
    }

    private fun resolveVenueRules(brokerSymbol: String): MT5VenueRules? {
        val qktSymbol = "${profile.name.uppercase()}:${mt5Symbol.toQkt(brokerSymbol)}"
        profile.instrumentOverrides[qktSymbol]?.let { return MT5VenueRules.of(it) }
        symbolMeta[brokerSymbol]?.let { return MT5VenueRules.of(it) }
        val fetched =
            runCatching { client.getSymbolInfo(brokerSymbol) }
                .onFailure { e ->
                    log.warn("MT5Broker ${profile.name} getSymbolInfo($brokerSymbol) failed: ${e.message}")
                }.getOrNull() ?: return null
        symbolMeta[brokerSymbol] = fetched
        return MT5VenueRules.of(fetched)
    }

    /**
     * Prepare [wire] for placement: quantize volume + prices, enforce stops level.
     *
     * Volume rounds DOWN to `volume_step`; price fields round to `digits` decimals
     * (HALF_EVEN); SL/TP within `tradeStopsLevel × pointSize` of entry are rejected
     * pre-flight. The venue would reject these anyway — surfacing locally avoids the
     * gateway round-trip and gives the strategy a structured reason string. When venue
     * rules are unavailable the original wire is passed through unchanged so the venue's
     * own rejection becomes the surfaced error.
     */
    fun prepareForPlacement(wire: MT5OrderRequest): PrepareResult {
        val rules =
            resolveVenueRules(wire.symbol) ?: run {
                log.warn(
                    "MT5Broker ${profile.name} no venue rules for ${wire.symbol}; " +
                        "sending unrounded wire (volume=${wire.volume.toPlainString()})",
                )
                return PrepareResult.Ok(wire)
            }
        val quantizedVolume =
            if (rules.volumeStep.signum() > 0) {
                wire.volume.divide(rules.volumeStep, 0, RoundingMode.DOWN).multiply(rules.volumeStep)
            } else {
                wire.volume
            }
        if (quantizedVolume < rules.volumeMin) {
            return PrepareResult.Reject(
                "quantized volume below venue volumeMin for ${wire.symbol} (input=${wire.volume.toPlainString()})",
            )
        }
        if (rules.volumeMax != null && quantizedVolume > rules.volumeMax) {
            return PrepareResult.Reject(
                "quantized volume above venue volumeMax for ${wire.symbol} (input=${wire.volume.toPlainString()})",
            )
        }
        val digits = rules.digits.coerceAtLeast(0)

        fun roundPrice(p: BigDecimal?): BigDecimal? = p?.setScale(digits, RoundingMode.HALF_EVEN)

        val quantized =
            wire.copy(
                volume = quantizedVolume,
                price = roundPrice(wire.price),
                sl = roundPrice(wire.sl),
                tp = roundPrice(wire.tp),
                stopLimit = roundPrice(wire.stopLimit),
            )
        // Stops-level enforcement: MT5 rejects orders whose SL/TP is closer to the entry
        // than `tradeStopsLevel × pointSize`. Reject locally with a structured reason so
        // strategy logs surface the cause without parsing gateway error blobs.
        if (rules.tradeStopsLevelPoints > 0 &&
            rules.pointSize.signum() > 0 &&
            quantized.price != null
        ) {
            val minDistance = rules.pointSize.multiply(BigDecimal(rules.tradeStopsLevelPoints))
            val current = priceTracker?.lastPrice("${profile.name.uppercase()}:${mt5Symbol.toQkt(wire.symbol)}")
            if (current != null && (quantized.price - current).abs() < minDistance) {
                return PrepareResult.Reject(
                    "entry too close to current price for ${wire.symbol}: " +
                        "distance=${(quantized.price - current).abs().toPlainString()} " +
                        "min=${minDistance.toPlainString()}",
                )
            }
            for ((field, value) in listOf("sl" to quantized.sl, "tp" to quantized.tp)) {
                if (value != null && value.signum() > 0) {
                    val distance = (quantized.price - value).abs()
                    if (distance < minDistance) {
                        return PrepareResult.Reject(
                            "$field too close to entry for ${wire.symbol}: " +
                                "distance=${distance.toPlainString()} min=${minDistance.toPlainString()} " +
                                "(tradeStopsLevel=${rules.tradeStopsLevelPoints}, pointSize=${rules.pointSize.toPlainString()})",
                        )
                    }
                }
            }
        }
        return PrepareResult.Ok(quantized)
    }
}
