package com.qkt.derivatives.options.pricing

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * The standard normal distribution. [cdf] uses Abramowitz & Stegun, *Handbook of Mathematical
 * Functions* (1964), formula 26.2.17, whose absolute error is below 7.5e-8, made exactly symmetric by
 * evaluating the upper tail and reflecting; [pdf] is exact.
 */
object NormalDistribution {
    private const val P = 0.2316419
    private const val B1 = 0.319381530
    private const val B2 = -0.356563782
    private const val B3 = 1.781477937
    private const val B4 = -1.821255978
    private const val B5 = 1.330274429
    private val inverseSqrtTwoPi = 1.0 / sqrt(2.0 * PI)

    /** Probability that a standard normal variable is at most [x]. */
    fun cdf(x: Double): Double {
        val t = 1.0 / (1.0 + P * abs(x))
        val poly = t * (B1 + t * (B2 + t * (B3 + t * (B4 + t * B5))))
        val upperTail = pdf(x) * poly
        return if (x >= 0.0) 1.0 - upperTail else upperTail
    }

    /** Standard normal density at [x]. */
    fun pdf(x: Double): Double = inverseSqrtTwoPi * exp(-0.5 * x * x)
}
