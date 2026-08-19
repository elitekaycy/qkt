package com.qkt.dsl.portfolio.fixture

import java.math.BigDecimal
import java.nio.file.Path

/**
 * One generated portfolio scenario, including all on-disk files and the invariants the
 * trace assertions should verify.
 */
data class PortfolioVariant(
    val name: String,
    val strategies: Map<String, String>,
    val portfolio: String,
    val config: String,
    val expected: ExpectedInvariants,
) {
    /** Write all files into [root], returning the path to the portfolio `.qkt` file. */
    fun materialize(root: Path): Path {
        for ((fileName, content) in strategies) {
            root.resolve(fileName).toFile().writeText(content)
        }
        val portfolioPath = root.resolve("$name.qkt")
        portfolioPath.toFile().writeText(portfolio)
        root.resolve("qkt.config.yaml").toFile().writeText(config)
        return portfolioPath
    }
}

/**
 * Invariants the test harness asserts for a variant. Many fields are optional because not every
 * variant exercises every feature.
 */
data class ExpectedInvariants(
    val capital: BigDecimal? = null,
    val allocationByAlias: Map<String, BigDecimal> = emptyMap(),
    val regimeWeightsByState: Map<String, Map<String, BigDecimal>> = emptyMap(),
    val alwaysRunAliases: Set<String> = emptySet(),
    val conditionalAliases: Set<String> = emptySet(),
    val holdAliases: Set<String> = emptySet(),
    val gateToggleBarCount: Int? = null,
    val expectsBookRisk: Boolean = false,
    val expectedMinTrades: Int = 0,
    val expectedRiskRejections: Boolean = false,
    val expectedHaltReason: String? = null,
)
