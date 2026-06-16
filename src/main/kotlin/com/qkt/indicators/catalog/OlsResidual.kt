package com.qkt.indicators.catalog

import com.qkt.common.Money
import com.qkt.indicators.MultiIndicator
import java.math.BigDecimal
import kotlin.math.abs

/**
 * Rolling ordinary-least-squares residual of a dependent series on one or more regressor
 * series over the last [period] aligned observations.
 *
 * Each bar feeds an aligned tuple `[y, x1, x2, …]` — the dependent value first, then one value
 * per regressor. The indicator fits `y = b0 + b1·x1 + … + bk·xk` over the window (least squares,
 * with an intercept) and reports the **latest** bar's residual: the part of `y` the regressors do
 * not explain, `y_last − (b0 + b1·x1_last + …)`. A residual far from zero means the dependent
 * series moved on its own, decoupled from the factors the regressors stand in for.
 *
 * e.g. fit GBPUSD returns on EURUSD + AUDUSD returns over 96 bars — a large positive residual
 * means cable rallied more than the broad-dollar move explains.
 *
 * Returns null until [period] observations are seen, and null when the regressors are collinear
 * or constant over the window (the normal-equations matrix is singular, so the fit is undefined),
 * consistent with the "null = skip this bar" contract. O(period · k²) per value.
 */
class OlsResidual(
    private val period: Int,
    private val regressorCount: Int,
) : MultiIndicator {
    init {
        require(regressorCount >= 1) { "OlsResidual needs at least 1 regressor: $regressorCount" }
        require(period > regressorCount + 1) {
            "OlsResidual.period ($period) must exceed regressors + 1 (${regressorCount + 1}) to fit a residual"
        }
    }

    // Each row is the aligned tuple [y, x1, …, xk]. Capacity bounded to [period].
    private val rows: ArrayDeque<DoubleArray> = ArrayDeque(period)

    override val warmupBars: Int = period

    override val isReady: Boolean
        get() = rows.size >= period

    override fun update(values: List<BigDecimal>) {
        require(values.size == regressorCount + 1) {
            "OlsResidual expects ${regressorCount + 1} values (dependent + $regressorCount regressors), " +
                "got ${values.size}"
        }
        rows.addLast(DoubleArray(values.size) { values[it].toDouble() })
        if (rows.size > period) rows.removeFirst()
    }

    override fun value(): BigDecimal? {
        if (!isReady) return null
        val m = regressorCount + 1 // parameters including the intercept
        // Normal equations XᵀX·b = Xᵀy, with each design row [1, x1, …, xk].
        val xtx = Array(m) { DoubleArray(m) }
        val xty = DoubleArray(m)
        for (row in rows) {
            val y = row[0]
            val design = DoubleArray(m)
            design[0] = 1.0
            for (j in 1 until m) design[j] = row[j]
            for (a in 0 until m) {
                xty[a] += design[a] * y
                for (b in 0 until m) xtx[a][b] += design[a] * design[b]
            }
        }
        val coeffs = solve(xtx, xty) ?: return null
        val last = rows.last()
        var predicted = coeffs[0]
        for (j in 1 until m) predicted += coeffs[j] * last[j]
        val residual = last[0] - predicted
        if (residual.isNaN() || residual.isInfinite()) return null
        return BigDecimal.valueOf(residual).setScale(Money.SCALE, Money.ROUNDING)
    }

    /**
     * Solve `a·x = b` by Gaussian elimination with partial pivoting. Returns null when the
     * matrix is singular (a pivot is effectively zero — collinear or constant regressors).
     */
    private fun solve(
        a: Array<DoubleArray>,
        b: DoubleArray,
    ): DoubleArray? {
        val n = b.size
        val aug =
            Array(n) { i ->
                DoubleArray(n + 1).also { r ->
                    for (j in 0 until n) r[j] = a[i][j]
                    r[n] = b[i]
                }
            }
        for (col in 0 until n) {
            var pivot = col
            for (r in col + 1 until n) {
                if (abs(aug[r][col]) > abs(aug[pivot][col])) pivot = r
            }
            if (abs(aug[pivot][col]) < SINGULAR_EPS) return null
            val swap = aug[col]
            aug[col] = aug[pivot]
            aug[pivot] = swap
            for (r in 0 until n) {
                if (r == col) continue
                val factor = aug[r][col] / aug[col][col]
                for (c in col..n) aug[r][c] -= factor * aug[col][c]
            }
        }
        return DoubleArray(n) { i -> aug[i][n] / aug[i][i] }
    }

    private companion object {
        /** A pivot smaller than this in magnitude is treated as zero (singular design matrix). */
        const val SINGULAR_EPS = 1e-12
    }
}
