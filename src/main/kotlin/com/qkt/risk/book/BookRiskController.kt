package com.qkt.risk.book

import com.qkt.common.Money
import java.math.BigDecimal

private const val NOT_FILLED = -1L

/**
 * How many samples an approved order may stay reserved without a fill, reject or cancel. Bounds every
 * reservation so none can leak: a resting order is not counted after this (it is not counted at all
 * without reservations), and an order that never reports back cannot slowly starve the book.
 */
private const val UNRESOLVED_TTL_SAMPLES = 2

/**
 * The book-risk brain. Fed a [BookSnapshot] each sample (by the measurement monitor), it refreshes an
 * immutable [BookRiskState] the pre-trade gate and order sizing read. Every output is a deterministic
 * function of the snapshots seen plus config, so the same controller produces the same decisions in
 * backtest and live.
 *
 * It owns three controls: exposure-limit state, the drawdown de-risk factor (via [DeRiskLadder]), and
 * dynamic allocation weights (inverse-vol / ERC + optional vol-targeting) recomputed on the rebalance
 * cadence from the rolling cross-strategy covariance.
 */
class BookRiskController(
    private val config: BookRiskConfig,
    private val capital: BigDecimal,
    private val annualization: BigDecimal = BigDecimal("252"),
) {
    private val ladder = config.deRisk?.let { DeRiskLadder(it.ladder) }
    private val allocation = config.allocation
    private var peakEquity = capital

    // Online cross-strategy return covariance (constant capital base), for dynamic allocation.
    private var ids: List<String> = emptyList()
    private val prevPnl = HashMap<String, BigDecimal>()
    private val sumR = HashMap<String, BigDecimal>()
    private val sumR2 = HashMap<String, BigDecimal>()
    private val sumCross = HashMap<Pair<String, String>, BigDecimal>()
    private var count = 0
    private var barCount = 0
    private var weights: Map<String, BigDecimal> = emptyMap()

    /** Target fractions supplied by a [com.qkt.dsl.portfolio.PortfolioGate] for [AllocationMethod.REGIME_WEIGHTED]. */
    private var regimeWeights: Map<String, BigDecimal> = emptyMap()

    @Volatile
    private var current: BookRiskState = BookRiskState(capital, Money.ZERO, emptyMap(), config.limits)

    /**
     * Approved risk-increasing orders that no sample carries yet, keyed by [bookReservationKey].
     *
     * The sampled state only moves when a sample is taken -- live, once per `riskIntervalMs` from
     * FILLED positions -- so without these every order checked inside one sample window saw the same
     * exposure and children entering together all passed a cap that admits one. Measured live on
     * 2026-09-10: three 0.01-lot entries (~3,239 notional) cleared a 1,998.53 gross cap, two of them
     * submitted 1 ms apart. An approved order is counted from approval until a sample that already
     * holds its position ([markFilled] then [onSample]), or until it is refused, rejected or cancelled
     * ([release]), or until [UNRESOLVED_TTL_SAMPLES] samples pass with no word from it.
     */
    private val reservationLock = Any()
    private val reservations = LinkedHashMap<String, Reservation>()
    private var startedSamples = 0L

    private class Reservation(
        val symbol: String,
        val signedNotional: BigDecimal,
    ) {
        var filledAtSample: Long = NOT_FILLED
        var unresolvedSamples: Int = 0
    }

    /**
     * Set the current regime-weight vector. Keys must match the strategy ids the controller sees in
     * [BookSnapshot.perStrategyPnl]. Empty weights leave allocation unchanged (scale = 1.0).
     *
     * The allocation weights are recomputed immediately so that the current bar's orders are scaled
     * by the regime that is active right now, not the regime from the previous sample.
     */
    fun setRegimeWeights(weights: Map<String, BigDecimal>) {
        regimeWeights = weights
        if (ids.isEmpty() && weights.isNotEmpty()) {
            ids = weights.keys.sorted()
        }
        this.weights = computeRegimeWeights()
        current = current.copy(allocationWeights = this.weights)
    }

    /**
     * Fold a book sample in. [startedAt] is the value [beginSample] returned BEFORE the sample's legs
     * were gathered; a filled reservation is dropped only by a sample that began after its fill was
     * marked, because only such a sample is guaranteed to hold the position. Callers that build and
     * apply the snapshot in one step on the thread that processes fills (the backtest monitor) can
     * leave it to default: the sample then begins at this call.
     */
    fun onSample(
        snapshot: BookSnapshot,
        startedAt: Long = beginSample(),
    ) {
        if (snapshot.bookEquity > peakEquity) peakEquity = snapshot.bookEquity
        val drawdown =
            if (peakEquity.signum() > 0) {
                peakEquity.subtract(snapshot.bookEquity).divide(peakEquity, Money.CONTEXT).max(Money.ZERO)
            } else {
                Money.ZERO
            }
        val factor = ladder?.factorFor(drawdown) ?: BigDecimal.ONE

        if (allocation != null && capital.signum() > 0) {
            if (allocation.method == AllocationMethod.REGIME_WEIGHTED) {
                ids = snapshot.perStrategyPnl.keys.sorted()
                weights = computeWeights()
            } else {
                foldReturns(snapshot.perStrategyPnl)
                barCount += 1
                val every = maxOf(1, allocation.rebalanceEveryBars)
                if (count >= 2 && barCount % every == 0) weights = computeWeights()
            }
        }

        current =
            BookRiskState(
                capital = capital,
                grossExposure = snapshot.exposure.gross,
                perSymbolNet = snapshot.exposure.perSymbolNet,
                limits = config.limits,
                deRiskFactor = factor,
                allocationWeights = weights,
            )
        synchronized(reservationLock) {
            val iterator = reservations.values.iterator()
            while (iterator.hasNext()) {
                val r = iterator.next()
                if (r.filledAtSample != NOT_FILLED) {
                    if (r.filledAtSample < startedAt) iterator.remove()
                } else if (++r.unresolvedSamples >= UNRESOLVED_TTL_SAMPLES) {
                    iterator.remove()
                }
            }
        }
    }

    /** The raw last sample. Sizing and dashboards read this; only [checkAndReserve] adds reservations. */
    fun state(): BookRiskState = current

    /** Marks the start of a sample: call BEFORE gathering the legs that will become its snapshot. */
    fun beginSample(): Long = synchronized(reservationLock) { ++startedSamples }

    /**
     * Pre-trade check and reservation in one atomic step: would adding [signedNotional] on [symbol]
     * breach a cap, counting the last sample AND every approved order it does not carry yet? If not,
     * the order is reserved under [key] before the lock is released, so a second child checking at
     * the same moment sees it. Returns the breach reason, or null when allowed.
     */
    fun checkAndReserve(
        key: String,
        symbol: String,
        signedNotional: BigDecimal,
    ): String? =
        synchronized(reservationLock) {
            val breach = withReservations(current).limitBreach(symbol, signedNotional)
            if (breach == null && config.limits != null && capital.signum() > 0) {
                reservations[key] = Reservation(symbol, signedNotional)
            }
            breach
        }

    /** The order was refused downstream, rejected by the venue, or cancelled: free its headroom now. */
    fun release(key: String) {
        synchronized(reservationLock) { reservations.remove(key) }
    }

    /** The order filled. It keeps counting until a sample that began after this call carries it. */
    fun markFilled(key: String) {
        synchronized(reservationLock) {
            val r = reservations[key] ?: return
            if (r.filledAtSample == NOT_FILLED) r.filledAtSample = startedSamples
        }
    }

    fun pendingReservations(): Int = synchronized(reservationLock) { reservations.size }

    private fun withReservations(state: BookRiskState): BookRiskState {
        if (reservations.isEmpty()) return state
        var gross = state.grossExposure
        val net = HashMap<String, BigDecimal>(state.perSymbolNet)
        for (r in reservations.values) {
            gross = gross.add(r.signedNotional.abs())
            net[r.symbol] = (net[r.symbol] ?: Money.ZERO).add(r.signedNotional)
        }
        return state.copy(grossExposure = gross, perSymbolNet = net)
    }

    private fun foldReturns(perStrategyPnl: Map<String, BigDecimal>) {
        if (ids.isEmpty()) {
            ids = perStrategyPnl.keys.sorted()
            for (id in ids) {
                sumR[id] = Money.ZERO
                sumR2[id] = Money.ZERO
            }
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) sumCross[ids[i] to ids[j]] = Money.ZERO
            }
        }
        if (prevPnl.isNotEmpty()) {
            val r = HashMap<String, BigDecimal>(ids.size)
            for (id in ids) {
                val cur = perStrategyPnl[id] ?: Money.ZERO
                val ri = cur.subtract(prevPnl.getValue(id)).divide(capital, Money.CONTEXT)
                r[id] = ri
                sumR[id] = sumR.getValue(id).add(ri)
                sumR2[id] = sumR2.getValue(id).add(ri.multiply(ri, Money.CONTEXT))
            }
            for (i in ids.indices) {
                for (j in i + 1 until ids.size) {
                    val key = ids[i] to ids[j]
                    val prod = r.getValue(ids[i]).multiply(r.getValue(ids[j]), Money.CONTEXT)
                    sumCross[key] = sumCross.getValue(key).add(prod)
                }
            }
            count += 1
        }
        for (id in ids) prevPnl[id] = perStrategyPnl[id] ?: Money.ZERO
    }

    private fun cov(
        i: String,
        j: String,
    ): BigDecimal {
        val n = BigDecimal(count)
        val meanI = sumR.getValue(i).divide(n, Money.CONTEXT)
        val sumXY = if (i == j) sumR2.getValue(i) else (sumCross[i to j] ?: sumCross.getValue(j to i))
        return sumXY
            .subtract(
                meanI.multiply(sumR.getValue(j), Money.CONTEXT),
            ).divide(BigDecimal(count - 1), Money.CONTEXT)
    }

    private fun computeWeights(): Map<String, BigDecimal> {
        val a = allocation ?: return emptyMap()
        val raw =
            when (a.method) {
                AllocationMethod.FIXED -> equalWeights(ids)
                AllocationMethod.INVERSE_VOL -> {
                    val variances = ids.associateWith { cov(it, it) }
                    inverseVol(variances)
                }
                AllocationMethod.ERC -> erc(ids, ::cov)
                AllocationMethod.REGIME_WEIGHTED -> return computeRegimeWeights()
            }
        // Express weights as a tilt around 1.0 (FIXED -> all 1.0) so they overlay the static
        // CAPITAL x WEIGHT rather than replace it.
        val n = BigDecimal(ids.size)
        val tilt = raw.mapValues { (_, w) -> w.multiply(n, Money.CONTEXT) }
        val targeted =
            if (a.targetVol != null) {
                volTarget(tilt, ids, ::cov, annualization, a.targetVol, a.maxLeverage)
            } else {
                tilt
            }
        return targeted.mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) }
    }

    private fun computeRegimeWeights(): Map<String, BigDecimal> {
        if (regimeWeights.isEmpty()) return emptyMap()
        return ids
            .associateWith { regimeWeights[it] ?: Money.ZERO }
            .mapValues { it.value.setScale(Money.SCALE, Money.ROUNDING) }
    }
}
