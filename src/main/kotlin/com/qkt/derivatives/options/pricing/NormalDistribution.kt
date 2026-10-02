package com.qkt.derivatives.options.pricing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The standard normal distribution. [cdf] is Hart's double-precision algorithm as published by
 * G. West, "Better approximations to cumulative normal functions", Wilmott Magazine (2005): a
 * rational function below |x| = 7.07 and a continued fraction beyond, exact to double precision in
 * absolute terms and to about 1e-8 relative in the far tails (|x| > 37 underflows to 0 or 1). The
 * lower tail is computed directly and the upper one by reflection, so it is symmetric by
 * construction. [pdf] is exact.
 */
object NormalDistribution {
    private val inverseSqrtTwoPi = 1.0 / sqrt(2.0 * PI)
    private val numerator =
        doubleArrayOf(
            3.52624965998911E-02,
            0.700383064443688,
            6.37396220353165,
            33.912866078383,
            112.079291497871,
            221.213596169931,
            220.206867912376,
        )
    private val denominator =
        doubleArrayOf(
            8.83883476483184E-02,
            1.75566716318264,
            16.064177579207,
            86.7807322029461,
            296.564248779674,
            637.333633378831,
            793.826512519948,
            440.413735824752,
        )

    /** Probability that a standard normal variable is at most [x]. */
    fun cdf(x: Double): Double {
        val lowerTail = lowerTail(abs(x))
        return if (x > 0.0) 1.0 - lowerTail else lowerTail
    }

    /** Standard normal density at [x]. */
    fun pdf(x: Double): Double = inverseSqrtTwoPi * exp(-0.5 * x * x)

    /** N(−a) for a ≥ 0. */
    private fun lowerTail(a: Double): Double {
        if (a > 37.0) return 0.0
        val gaussian = exp(-a * a / 2)
        if (a < 7.07106781186547) return gaussian * horner(numerator, a) / horner(denominator, a)
        var fraction = a + 0.65
        fraction = a + 4 / fraction
        fraction = a + 3 / fraction
        fraction = a + 2 / fraction
        fraction = a + 1 / fraction
        return gaussian / fraction / 2.506628274631
    }

    private fun horner(
        coefficients: DoubleArray,
        x: Double,
    ): Double = coefficients.fold(0.0) { acc, c -> acc * x + c }
}
