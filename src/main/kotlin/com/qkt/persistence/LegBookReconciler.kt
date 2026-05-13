package com.qkt.persistence

import com.qkt.common.Side
import com.qkt.positions.LegBook
import com.qkt.positions.Position
import java.math.BigDecimal
import org.slf4j.LoggerFactory

/**
 * Three-way merge of persisted leg state and broker-side positions at strategy startup.
 *
 * Inputs:
 *   - The previously persisted [PersistedLegBook] for `(strategyId, symbol)`, if any.
 *   - The broker's view of open positions on `symbol`, recovered via the broker's
 *     state-recovery path (e.g. [com.qkt.broker.mt5.MT5StateRecovery]).
 *
 * Output: an [Outcome] the deploy path uses to decide how to proceed. See [reconcile].
 */
class LegBookReconciler(
    private val persistor: StatePersistor,
    private val quantityTolerance: BigDecimal = BigDecimal("0.001"),
    private val priceToleranceFraction: BigDecimal = BigDecimal("0.0001"),
) {
    private val log = LoggerFactory.getLogger(LegBookReconciler::class.java)

    sealed class Outcome {
        data class Attached(
            val legBook: LegBook,
        ) : Outcome()

        data class Mismatch(
            val details: String,
        ) : Outcome()

        data object NothingPersisted : Outcome()
    }

    /**
     * Reconcile a single `(strategyId, symbol)` pair.
     *
     * [brokerPositions] is the list of broker-side positions on this symbol. Most
     * brokers report at most one net position per symbol, but multi-position-capable
     * brokers (Bybit linear) may report several.
     */
    fun reconcile(
        strategyId: String,
        symbol: String,
        brokerPositions: List<Position>,
    ): Outcome {
        val persisted = persistor.loadLegBook(strategyId, symbol)

        return when {
            brokerPositions.isEmpty() && persisted == null -> Outcome.NothingPersisted

            brokerPositions.isEmpty() && persisted != null -> {
                log.warn(
                    "Reconcile: persisted state for $strategyId/$symbol exists but broker reports no positions; wiping persisted state",
                )
                persistor.saveLegBook(strategyId, symbol, LegBook(symbol))
                Outcome.NothingPersisted
            }

            brokerPositions.isNotEmpty() && persisted == null ->
                Outcome.Mismatch(
                    "broker reports ${brokerPositions.size} position(s) for $strategyId/$symbol, no persisted state",
                )

            else -> attemptAttach(strategyId, symbol, brokerPositions, persisted!!)
        }
    }

    private fun attemptAttach(
        strategyId: String,
        symbol: String,
        brokerPositions: List<Position>,
        persisted: PersistedLegBook,
    ): Outcome {
        val unmatchedPersisted = persisted.legs.toMutableList()
        val matchedLegs = mutableListOf<PersistedLeg>()

        for (pos in brokerPositions) {
            val (side, qty) = decompose(pos.quantity)
            val match = unmatchedPersisted.firstOrNull { leg -> isMatch(leg, side, qty, pos.avgEntryPrice) }
            if (match == null) {
                return Outcome.Mismatch(
                    "broker position $side qty=$qty @ ${pos.avgEntryPrice} on $symbol has no matching persisted leg " +
                        "for $strategyId (persisted: ${persisted.legs.size} leg(s))",
                )
            }
            matchedLegs.add(match)
            unmatchedPersisted.remove(match)
        }

        if (unmatchedPersisted.isNotEmpty()) {
            return Outcome.Mismatch(
                "persisted ${unmatchedPersisted.size} leg(s) for $strategyId/$symbol have no matching broker position",
            )
        }

        val book = LegBook(symbol)
        matchedLegs.forEach { book.add(it.toPositionLeg()) }
        return Outcome.Attached(book)
    }

    private fun decompose(signedQty: BigDecimal): Pair<Side, BigDecimal> =
        if (signedQty.signum() >= 0) Side.BUY to signedQty else Side.SELL to signedQty.negate()

    private fun isMatch(
        leg: PersistedLeg,
        side: Side,
        absQty: BigDecimal,
        entryPrice: BigDecimal,
    ): Boolean {
        if (leg.side != side) return false
        if (leg.quantity.subtract(absQty).abs() > quantityTolerance) return false
        val priceTol = leg.entryPrice.multiply(priceToleranceFraction).abs()
        return leg.entryPrice.subtract(entryPrice).abs() <= priceTol
    }
}
